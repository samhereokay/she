import base64
import hashlib
import hmac
import json
import logging
import time
from typing import Any, Optional
from fastapi import APIRouter, Depends, HTTPException, status, BackgroundTasks, Request
from fastapi.responses import HTMLResponse
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_verified_user
from app.core.rate_limit import email_verify_limiter
from app.core.kyc_validator import validate_email_kyc, validate_phone_kyc, normalize_phone_number
from app.core.security import create_canonical_command_bytes, compute_truncated_hmac, decrypt_pairing_secret
from app.core.config import settings
from app.db.session import get_db
from app.db.models import User, ProtectedDevice, DeviceCommand, VerificationChallenge, AuditEvent, utc_now, generate_uuid
from app.schemas.auth import (
    EmailSendRequest,
    EmailConfirmRequest,
    EmailVerifyResponse,
    PhoneStartRequest,
    PhoneConfirmRequest,
    PhoneVerifyResponse
)
from app.verification.engine import issue, confirm
from app.verification.mailer import send_verification_email

logger = logging.getLogger("uvicorn.error")

router = APIRouter(prefix="/verify", tags=["Verification"])


def create_email_verification_token(challenge_id: str, user_id: str, email: str, expires_epoch: int) -> str:
    """Creates a signed HMAC token bound to challenge, user, email, and expiration."""
    payload = f"{challenge_id}:{user_id}:{email.strip().lower()}:{expires_epoch}"
    secret = (settings.VERIFY_SECRET or settings.SECRET_KEY).encode("utf-8")
    sig = hmac.new(secret, payload.encode("utf-8"), hashlib.sha256).hexdigest()
    b64_payload = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("utf-8").rstrip("=")
    return f"{b64_payload}.{sig}"


def verify_email_verification_token(token: str) -> tuple[bool, Optional[dict], str]:
    """Validates signature, structure, and expiration of an email verification link token."""
    try:
        parts = token.strip().split(".")
        if len(parts) != 2:
            return False, None, "Malformed verification token structure"
        b64_payload, provided_sig = parts[0], parts[1]
        rem = len(b64_payload) % 4
        padded = b64_payload + ("=" * (4 - rem) if rem else "")
        raw_payload = base64.urlsafe_b64decode(padded.encode("utf-8")).decode("utf-8")
        secret = (settings.VERIFY_SECRET or settings.SECRET_KEY).encode("utf-8")
        expected_sig = hmac.new(secret, raw_payload.encode("utf-8"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(provided_sig, expected_sig):
            return False, None, "Invalid cryptographic token signature"
        p_parts = raw_payload.split(":")
        if len(p_parts) != 4:
            return False, None, "Invalid payload structure"
        challenge_id, user_id, email, exp_str = p_parts[0], p_parts[1], p_parts[2], p_parts[3]
        if time.time() > int(exp_str):
            return False, None, "Verification link has expired"
        return True, {
            "challenge_id": challenge_id,
            "user_id": user_id,
            "email": email,
            "expires_epoch": int(exp_str)
        }, ""
    except Exception as e:
        return False, None, f"Failed to parse token: {str(e)}"


def render_html_page(title: str, message: str, action_html: str = "", is_error: bool = False, is_success: bool = False) -> str:
    """Renders a clean, dark-mode responsive HTML page for web confirmation."""
    accent_color = "#ef4444" if is_error else ("#10b981" if is_success else "#0284c7")
    return f"""<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title} - SHE Anti-Theft</title>
  <style>
    body {{
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
      background-color: #0b1120;
      color: #f1f5f9;
      margin: 0;
      padding: 24px;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      box-sizing: border-box;
    }}
    .card {{
      max-width: 480px;
      width: 100%;
      background-color: #1e293b;
      border: 1px solid #334155;
      border-radius: 14px;
      padding: 32px 28px;
      box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.5);
      text-align: center;
    }}
    .brand {{
      font-size: 13px;
      letter-spacing: 2px;
      text-transform: uppercase;
      font-weight: 700;
      color: #38bdf8;
      margin-bottom: 16px;
    }}
    h1 {{
      font-size: 22px;
      font-weight: 700;
      color: {accent_color};
      margin: 0 0 16px 0;
    }}
    p {{
      font-size: 15px;
      line-height: 1.6;
      color: #94a3b8;
      margin: 0 0 24px 0;
    }}
    .footer-note {{
      font-size: 13px;
      color: #64748b;
      margin-top: 24px;
      margin-bottom: 0;
    }}
  </style>
</head>
<body>
  <div class="card">
    <div class="brand">SHE Anti-Theft & Recovery</div>
    <h1>{title}</h1>
    <p>{message}</p>
    {action_html}
    <p class="footer-note">You can safely close this browser window and return to the SHE Admin app.</p>
  </div>
</body>
</html>"""


# -----------------------------------------------------------------------------
# Email Verification (OTP via SMTP / Mailpit)
# -----------------------------------------------------------------------------

@router.post("/email/send", status_code=status.HTTP_202_ACCEPTED)
def send_email_verification(
    req: EmailSendRequest,
    background_tasks: BackgroundTasks,
    request: Request,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Issue and dispatch a 6-digit cryptographic verification challenge to the account email.
    
    Accepts an optional new target email or defaults to user's registered email.
    Re-validates email against disposable lists and live MX checks before issuance.
    Dispatches email asynchronously via background tasks with 1-click verification link.
    """
    email_verify_limiter.check_rate_limit(request)
    target_email = (req.email or current_user.email).strip().lower()

    # Pre-filter KYC validation (disposable domains, RFC syntax, MX resolution)
    is_valid, err_msg = validate_email_kyc(target_email)
    if not is_valid:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=err_msg
        )

    # Check if target email belongs to another registered account
    existing = db.query(User).filter(User.email == target_email, User.id != current_user.id).first()
    if existing:
        if existing.email_verified_at is not None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Email already registered by another account."
            )
        else:
            # Reclaim unverified squatted email
            db.delete(existing)
            db.commit()

    # If updating to a new email address, update user record and reset verified timestamp
    if target_email != current_user.email.lower():
        current_user.email = target_email
        current_user.email_verified_at = None
        db.commit()
        db.refresh(current_user)

    # Issue challenge via cryptographic verification engine
    challenge, raw_code = issue(
        db=db,
        user_id=current_user.id,
        channel="email",
        target=target_email
    )

    from datetime import timezone
    exp_dt = challenge.expires_at
    if exp_dt.tzinfo is None:
        exp_dt = exp_dt.replace(tzinfo=timezone.utc)
    exp_epoch = int(exp_dt.timestamp())
    link_token = create_email_verification_token(challenge.id, current_user.id, target_email, exp_epoch)
    verify_url = f"{settings.SERVER_PUBLIC_URL.rstrip('/')}/api/v1/verify/email/link?token={link_token}"

    # Asynchronously dispatch email (zero credentials/codes in logs)
    background_tasks.add_task(send_verification_email, target_email, raw_code, verify_url=verify_url)

    resp = {
        "status": "challenge_issued",
        "channel": "email",
        "target": target_email,
        "expires_at": challenge.expires_at
    }
    if settings.DEBUG:
        resp["debug_code"] = raw_code
        resp["debug_verify_link"] = verify_url
    return resp


@router.get("/email/link", response_class=HTMLResponse)
def render_email_verification_link_page(token: str, db: Session = Depends(get_db)) -> Any:
    """
    Renders confirmation webpage for 1-click email verification.
    Displays target email and a confirmation button that submits POST /verify/email/link.
    """
    is_valid, payload, err = verify_email_verification_token(token)
    if not is_valid or not payload:
        return HTMLResponse(
            status_code=400,
            content=render_html_page(
                title="Verification Link Invalid or Expired",
                message=f"This email verification link is no longer valid ({err}). Please open the SHE Admin app and request a fresh verification link.",
                is_error=True
            )
        )

    # Check challenge in database
    challenge = db.query(VerificationChallenge).filter(
        VerificationChallenge.id == payload["challenge_id"],
        VerificationChallenge.user_id == payload["user_id"]
    ).first()

    if not challenge:
        return HTMLResponse(
            status_code=404,
            content=render_html_page(
                title="Verification Challenge Not Found",
                message="The requested verification session was not found. Please request a new code from the SHE Admin app.",
                is_error=True
            )
        )

    if challenge.consumed_at is not None:
        return HTMLResponse(
            status_code=400,
            content=render_html_page(
                title="Link Already Consumed",
                message="This verification link has already been used. If your account is still unverified, please request a new verification code from the SHE Admin app.",
                is_error=True
            )
        )

    confirm_form = f"""
    <form method="POST" action="/api/v1/verify/email/link">
      <input type="hidden" name="token" value="{token}" />
      <button type="submit" style="display: block; width: 100%; background-color: #0284c7; color: #ffffff; text-decoration: none; padding: 14px 28px; border: none; border-radius: 8px; font-weight: 700; font-size: 16px; cursor: pointer; transition: background-color 0.2s;">Confirm & Verify Email</button>
    </form>
    """

    return HTMLResponse(
        content=render_html_page(
            title="Confirm Email Verification",
            message=f"Click the button below to confirm ownership of <strong>{payload['email']}</strong> for your SHE account:",
            action_html=confirm_form
        )
    )


@router.post("/email/link", response_class=HTMLResponse)
async def process_email_verification_link(
    request: Request,
    token: Optional[str] = None,
    db: Session = Depends(get_db)
) -> Any:
    """
    Validates token, marks challenge as consumed, sets email_verified_at, and renders success page.
    """
    sub_token = token
    if not sub_token:
        try:
            form = await request.form()
            sub_token = form.get("token")
        except Exception:
            pass

    if not sub_token:
        return HTMLResponse(
            status_code=400,
            content=render_html_page(
                title="Missing Token",
                message="No verification token was provided in the request.",
                is_error=True
            )
        )

    is_valid, payload, err = verify_email_verification_token(sub_token)
    if not is_valid or not payload:
        return HTMLResponse(
            status_code=400,
            content=render_html_page(
                title="Invalid or Expired Link",
                message=f"Could not verify email: {err}. Please request a fresh verification link from the SHE Admin app.",
                is_error=True
            )
        )

    challenge = db.query(VerificationChallenge).filter(
        VerificationChallenge.id == payload["challenge_id"],
        VerificationChallenge.user_id == payload["user_id"]
    ).first()

    if not challenge or challenge.consumed_at is not None:
        return HTMLResponse(
            status_code=400,
            content=render_html_page(
                title="Link Already Used",
                message="This verification link has already been used. Please check your SHE Admin app.",
                is_error=True
            )
        )

    target_email = payload["email"].strip().lower()
    user = db.query(User).filter(User.id == payload["user_id"]).first()
    if not user:
        return HTMLResponse(
            status_code=404,
            content=render_html_page(
                title="Account Not Found",
                message="The associated account could not be found.",
                is_error=True
            )
        )

    # Check if another account verified this email
    other_verified = db.query(User).filter(
        User.email == target_email,
        User.id != user.id,
        User.email_verified_at != None
    ).first()
    if other_verified:
        return HTMLResponse(
            status_code=409,
            content=render_html_page(
                title="Verification Conflict",
                message="Another account has already verified ownership of this email address.",
                is_error=True
            )
        )

    now = utc_now()
    challenge.consumed_at = now
    user.email_verified_at = now

    # Remove any unverified competing registrations
    unverified_competitors = db.query(User).filter(
        User.email == target_email,
        User.id != user.id,
        User.email_verified_at == None
    ).all()
    for comp in unverified_competitors:
        db.delete(comp)

    db.add(AuditEvent(
        user_id=user.id,
        event_type="EMAIL_VERIFIED_LINK",
        details=f"Email '{target_email}' verified via 1-click web confirmation link."
    ))
    db.commit()

    return HTMLResponse(
        content=render_html_page(
            title="Email Verified Successfully!",
            message=f"Ownership of <strong>{target_email}</strong> has been successfully verified.<br><br>You can now return to the SHE Admin app. Your devices, commands, and location controls are unlocked.",
            is_success=True
        )
    )


@router.post("/email/debug-verify")
def debug_verify_email(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """Development-only instant email verification bypass."""
    if not settings.DEBUG:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="debug_bypass_disabled: Direct verification bypass is strictly restricted to development environments."
        )
    current_user.email_verified_at = utc_now()
    db.commit()
    return {"status": "verified", "email_verified_at": current_user.email_verified_at}


@router.post("/phone/debug-verify")
def debug_verify_phone(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """Development-only instant phone verification bypass."""
    if not settings.DEBUG:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="debug_bypass_disabled: Direct phone verification bypass is strictly restricted to development environments."
        )
    current_user.phone_verified_at = utc_now()
    if not current_user.fallback_phone_e164:
        current_user.fallback_phone_e164 = "+919876543210"
    db.commit()
    return {"status": "verified", "phone_verified_at": current_user.phone_verified_at}


@router.post("/email/confirm", response_model=EmailVerifyResponse)
def confirm_email_verification(
    req: EmailConfirmRequest,
    request: Request,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Validate the 6-digit email OTP challenge.
    
    Upon successful validation:
    - Sets user.email_verified_at = utc_now()
    - Marks challenge as consumed
    - Unlocks full access to devices, locations, and commands
    """
    email_verify_limiter.check_rate_limit(request)
    target_email = current_user.email.strip().lower()

    # Confirm via engine (enforces lock-out, expiry, timing attack resistance, replay prevention)
    confirm(
        db=db,
        user_id=current_user.id,
        channel="email",
        target=target_email,
        code=req.code
    )

    # Enforce uniqueness at email verification time:
    # Check if another account verified this email in the meantime
    other_verified = db.query(User).filter(
        User.email == target_email,
        User.id != current_user.id,
        User.email_verified_at != None
    ).first()
    if other_verified:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="email_already_verified_by_other: Another account has already verified ownership of this email address."
        )

    now = utc_now()
    current_user.email_verified_at = now

    # First to confirm wins: Purge any other unverified accounts that registered with this email
    unverified_competitors = db.query(User).filter(
        User.email == target_email,
        User.id != current_user.id,
        User.email_verified_at == None
    ).all()
    for comp in unverified_competitors:
        db.delete(comp)

    audit = AuditEvent(
        user_id=current_user.id,
        event_type="EMAIL_VERIFIED",
        details=f"Email address '{target_email}' successfully verified."
    )
    db.add(audit)
    db.commit()
    db.refresh(current_user)

    return EmailVerifyResponse(
        status="email_verified",
        email_verified_at=current_user.email_verified_at
    )


# -----------------------------------------------------------------------------
# -----------------------------------------------------------------------------
# Phone Verification (SMS via Paired Guardian Handset)
# -----------------------------------------------------------------------------

@router.post("/phone/start", status_code=status.HTTP_202_ACCEPTED)
def start_phone_verification(
    req: PhoneStartRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Initiate SMS ownership verification for offline emergency fallback beacons.
    
    CRITICAL SECURITY ENFORCEMENT:
    1. Caller must be an authenticated account owner (get_current_user).
    2. Phone number is validated against international E.164 syntax, mobile ranges, and landline blocks.
    3. User must have a paired Guardian handset (ACTIVE or PENDING_CONFIRMATION). If none found, returns clear 409 error.
    4. Enqueues a cryptographically authenticated SEND_VERIFY_SMS command to the Guardian phone.
    5. Plaintext OTP code is NEVER exposed in the HTTP response to Admin or client.
    """
    clean_phone = (req.phone_number or "").strip()

    # 1. Phone KYC pre-filter & E.164 normalization
    is_valid, err_msg = validate_phone_kyc(clean_phone)
    if not is_valid:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=err_msg
        )
    e164_phone = normalize_phone_number(clean_phone)
    if not e164_phone:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Failed to normalize phone number to E.164 format."
        )

    # 2. Check if a Guardian phone is paired (ACTIVE or PENDING_CONFIRMATION)
    guardian_device = db.query(ProtectedDevice).filter(
        ProtectedDevice.user_id == current_user.id,
        ProtectedDevice.status.in_(["ACTIVE", "PENDING_CONFIRMATION"])
    ).first()

    if not guardian_device:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="no_active_guardian_device: A paired Guardian handset is required to dispatch the SMS verification challenge. Please pair your Guardian handset first or verify via email OTP."
        )

    # 3. If changing fallback number, revoke verified status
    if current_user.fallback_phone_e164 and current_user.fallback_phone_e164 != e164_phone:
        current_user.phone_verified_at = None
        current_user.fallback_phone_e164 = e164_phone
        db.commit()
        db.refresh(current_user)

    # 4. Issue cryptographic challenge via engine
    challenge, raw_code = issue(
        db=db,
        user_id=current_user.id,
        channel="sms",
        target=e164_phone
    )

    # 5. Enqueue SEND_VERIFY_SMS command to Guardian device
    max_seq = db.query(func.max(DeviceCommand.sequence_counter)).filter(
        DeviceCommand.protected_device_id == guardian_device.id
    ).scalar() or 0
    next_seq = max_seq + 1

    now_epoch = int(time.time())
    expiry_epoch = now_epoch + 600  # 10 minutes

    params_json = json.dumps({
        "to": e164_phone,
        "body": f"SHE verification code: {raw_code}. Share it only with the account owner."
    })

    cmd_id = generate_uuid()
    canonical_bytes = create_canonical_command_bytes(
        pairing_id=guardian_device.pairing_id or "",
        command_id=cmd_id,
        target_device_id=guardian_device.id,
        command_type="SEND_VERIFY_SMS",
        sequence_counter=next_seq,
        expiry_epoch=expiry_epoch,
        parameters_json=params_json
    )

    if not guardian_device.pairing_secret:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="no_pairing_secret: Guardian device must be re-paired to enable phone verification. Please unpair and re-pair your Guardian handset."
        )
    raw_pairing_secret = decrypt_pairing_secret(guardian_device.pairing_secret)
    pairing_secret_bytes = raw_pairing_secret.encode("utf-8")
    server_sig = compute_truncated_hmac(pairing_secret_bytes, canonical_bytes, hex_length=12)

    cmd = DeviceCommand(
        id=cmd_id,
        user_id=current_user.id,
        trusted_device_id=None,
        protected_device_id=guardian_device.id,
        pairing_id=guardian_device.pairing_id,
        command_type="SEND_VERIFY_SMS",
        sequence_counter=next_seq,
        expiry_epoch=expiry_epoch,
        parameters_json=params_json,
        admin_signature=server_sig,
        status="PENDING"
    )
    db.add(cmd)
    db.commit()

    logger.info(f"Enqueued SEND_VERIFY_SMS command {cmd.id} to Guardian {guardian_device.id}")

    resp = {
        "status": "challenge_issued",
        "channel": "sms",
        "target": e164_phone,
        "expires_at": challenge.expires_at,
        "command_id": cmd.id
    }
    if settings.DEBUG:
        resp["debug_code"] = raw_code
    return resp


@router.post("/phone/confirm", response_model=PhoneVerifyResponse)
def confirm_phone_verification(
    req: PhoneConfirmRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Validate the 6-digit SMS verification code forwarded from the emergency contact.
    
    Upon successful validation:
    - Sets user.fallback_phone_e164 = target
    - Sets user.phone_verified_at = utc_now()
    - Synchronizes verified emergency number to all paired Guardian devices
    - Unlocks offline SMS beacons and AT1 coordinate broadcasts
    """
    # Find target from the most recent active SMS challenge for this user
    active_challenge = db.query(VerificationChallenge).filter(
        VerificationChallenge.user_id == current_user.id,
        VerificationChallenge.channel == "sms",
        VerificationChallenge.consumed_at.is_(None)
    ).order_by(VerificationChallenge.created_at.desc()).first()

    if not active_challenge:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="challenge_invalid_or_consumed: No active phone verification challenge found. Request a new SMS code first."
        )

    target_phone = active_challenge.target

    # Confirm via engine
    confirm(
        db=db,
        user_id=current_user.id,
        channel="sms",
        target=target_phone,
        code=req.code
    )

    now = utc_now()
    current_user.fallback_phone_e164 = target_phone
    current_user.phone_verified_at = now

    # Promote any pending guardian devices to ACTIVE since owner confirmed via SMS challenge
    pending_devices = db.query(ProtectedDevice).filter(
        ProtectedDevice.user_id == current_user.id,
        ProtectedDevice.status == "PENDING_CONFIRMATION"
    ).all()
    for pd in pending_devices:
        pd.status = "ACTIVE"
        pd.confirmation_code = None

    # Synchronize verified fallback phone number to paired Guardian devices
    devices = db.query(ProtectedDevice).filter(ProtectedDevice.user_id == current_user.id).all()
    for dev in devices:
        dev.trusted_phone_number = target_phone
        # Enqueue authenticated SYNC_VERIFIED_PHONE command so Guardian updates its DE storage
        if dev.pairing_secret:
            try:
                max_seq = db.query(func.max(DeviceCommand.sequence_counter)).filter(
                    DeviceCommand.protected_device_id == dev.id
                ).scalar() or 0
                next_seq = max_seq + 1
                cmd_id = generate_uuid()
                expiry_epoch = int(time.time()) + 86400  # 24 hours
                params_json = json.dumps({"phone": target_phone})
                canonical_bytes = create_canonical_command_bytes(
                    pairing_id=dev.pairing_id,
                    command_id=cmd_id,
                    target_device_id=dev.id,
                    command_type="SYNC_VERIFIED_PHONE",
                    sequence_counter=next_seq,
                    expiry_epoch=expiry_epoch,
                    parameters_json=params_json
                )
                raw_pairing_secret = decrypt_pairing_secret(dev.pairing_secret)
                server_sig = compute_truncated_hmac(raw_pairing_secret.encode("utf-8"), canonical_bytes, hex_length=12)
                sync_cmd = DeviceCommand(
                    id=cmd_id,
                    user_id=current_user.id,
                    trusted_device_id=None,
                    protected_device_id=dev.id,
                    pairing_id=dev.pairing_id,
                    command_type="SYNC_VERIFIED_PHONE",
                    sequence_counter=next_seq,
                    expiry_epoch=expiry_epoch,
                    parameters_json=params_json,
                    admin_signature=server_sig,
                    status="PENDING"
                )
                db.add(sync_cmd)
            except Exception as e:
                logger.warning(f"Failed to enqueue SYNC_VERIFIED_PHONE for device {dev.id}: {e}")

    audit = AuditEvent(
        user_id=current_user.id,
        event_type="PHONE_VERIFIED",
        details="Fallback emergency phone number verified via Guardian SMS channel."
    )
    db.add(audit)
    db.commit()
    db.refresh(current_user)

    return PhoneVerifyResponse(
        status="phone_verified",
        fallback_phone_e164=current_user.fallback_phone_e164,
        phone_verified_at=current_user.phone_verified_at
    )
