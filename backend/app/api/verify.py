"""Zero-trust ownership challenge endpoints (Email OTP & SMS Phone Verification)."""

import json
import logging
import time
from typing import Any
from fastapi import APIRouter, Depends, HTTPException, status, BackgroundTasks
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_verified_user
from app.core.kyc_validator import validate_email_kyc, validate_phone_kyc, normalize_phone_number
from app.core.security import create_canonical_command_bytes, compute_truncated_hmac, decrypt_pairing_secret
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


# -----------------------------------------------------------------------------
# Email Verification (OTP via SMTP / Mailpit)
# -----------------------------------------------------------------------------

@router.post("/email/send", status_code=status.HTTP_202_ACCEPTED)
def send_email_verification(
    req: EmailSendRequest,
    background_tasks: BackgroundTasks,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Issue and dispatch a 6-digit cryptographic verification challenge to the account email.
    
    Accepts an optional new target email or defaults to user's registered email.
    Re-validates email against disposable lists and live MX checks before issuance.
    Dispatches email asynchronously via background tasks.
    """
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

    # Asynchronously dispatch email (zero credentials/codes in logs)
    background_tasks.add_task(send_verification_email, target_email, raw_code)

    return {
        "status": "challenge_issued",
        "channel": "email",
        "target": target_email,
        "expires_at": challenge.expires_at
    }


@router.post("/email/confirm", response_model=EmailVerifyResponse)
def confirm_email_verification(
    req: EmailConfirmRequest,
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
    target_email = current_user.email.strip().lower()

    # Confirm via engine (enforces lock-out, expiry, timing attack resistance, replay prevention)
    confirm(
        db=db,
        user_id=current_user.id,
        channel="email",
        target=target_email,
        code=req.code
    )

    now = utc_now()
    current_user.email_verified_at = now

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
# Phone Verification (SMS via Paired Guardian Handset)
# -----------------------------------------------------------------------------

@router.post("/phone/start", status_code=status.HTTP_202_ACCEPTED)
def start_phone_verification(
    req: PhoneStartRequest,
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Initiate SMS ownership verification for offline emergency fallback beacons.
    
    CRITICAL SECURITY ENFORCEMENT:
    1. Caller must have verified their account email address (get_verified_user).
    2. Phone number is validated against international E.164 syntax, mobile ranges, and landline blocks.
    3. User must have an ACTIVE paired Guardian handset. If none online/paired, returns clear retryable 409 error.
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

    # 2. Check if a Guardian phone is paired and active
    guardian_device = db.query(ProtectedDevice).filter(
        ProtectedDevice.user_id == current_user.id,
        ProtectedDevice.status == "ACTIVE"
    ).first()

    if not guardian_device:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="no_active_guardian_device: A paired, active Guardian handset is required to dispatch the SMS verification challenge. Please pair your Guardian handset first."
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

    # Plaintext code NEVER returned in response
    return {
        "status": "challenge_issued",
        "channel": "sms",
        "target": e164_phone,
        "expires_at": challenge.expires_at,
        "command_id": cmd.id
    }


@router.post("/phone/confirm", response_model=PhoneVerifyResponse)
def confirm_phone_verification(
    req: PhoneConfirmRequest,
    current_user: User = Depends(get_verified_user),
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

    # Synchronize verified fallback phone number to paired Guardian devices
    devices = db.query(ProtectedDevice).filter(ProtectedDevice.user_id == current_user.id).all()
    for dev in devices:
        dev.trusted_phone_number = target_phone

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
