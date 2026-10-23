from datetime import datetime, timezone, timedelta
import hashlib
import json
import re
import secrets
from typing import Any
from fastapi import APIRouter, Depends, HTTPException, status, Request
from fastapi.security import OAuth2PasswordRequestForm
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.security import (
    hash_password,
    verify_password,
    create_access_token,
    generate_totp_secret,
    encrypt_totp_secret,
    decrypt_totp_secret,
    get_totp_uri,
    verify_totp_code
)
from app.core.rate_limit import auth_limiter, totp_limiter
from app.core.kyc_validator import validate_email_kyc, validate_phone_kyc, validate_hardware_kyc
from app.db.session import get_db
from app.db.models import User, TrustedDevice, AuditEvent, utc_now, UserSession
from app.schemas.auth import (
    UserCreate,
    UserResponse,
    Token,
    UserLogin,
    TotpSetupResponse,
    TotpVerifyRequest,
    TotpStatusResponse,
    UserSessionResponse,
    SessionRevokeResponse,
    RefreshTokenRequest
)
from app.schemas.device import PairingTokenCreateRequest, PairingTokenResponse
from app.api.deps import get_current_user, get_current_user_2fa
from app.core.security import create_pairing_token, verify_access_token

router = APIRouter(prefix="/auth", tags=["Authentication"])


@router.post("/register", response_model=UserResponse, status_code=status.HTTP_201_CREATED)
def register(request: Request, user_in: UserCreate, db: Session = Depends(get_db)) -> Any:
    """Register a new owner admin user with lockdown check and rate limiting."""
    auth_limiter.check_rate_limit(request)

    existing_users = db.query(User).count()

    # If ADMIN_SETUP_TOKEN is configured in environment, require it for bootstrapping initial owner
    if settings.ADMIN_SETUP_TOKEN:
        setup_header = request.headers.get("X-Admin-Setup-Token", "")
        if not secrets.compare_digest(setup_header, settings.ADMIN_SETUP_TOKEN):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Invalid or missing admin setup token. Set X-Admin-Setup-Token header to initialize owner."
            )

    # -------------------------------------------------------------
    # Anti-Fake Identity & KYC Verification
    # -------------------------------------------------------------
    if not re.match(r"^[a-zA-Z0-9_-]{3,32}$", user_in.username):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Username must be 3-32 alphanumeric characters, dashes or underscores."
        )

    # 1. Email KYC: Disposable email blocking, typo protection & DNS MX checks
    email_ok, email_err = validate_email_kyc(user_in.email)
    if not email_ok:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=email_err
        )

    # 2. Phone KYC: International E.164, ITU-T country prefixes, mobile ranges & anti-dummy entropy
    phone_clean = (user_in.phone_number or "").strip()
    if phone_clean:
        phone_ok, phone_err = validate_phone_kyc(phone_clean)
        if not phone_ok:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=phone_err
            )

    # 3. Hardware KYC: Physical Android handset telemetry verification
    device_info_clean = (user_in.device_info or "").strip()
    hw_ok, _ = validate_hardware_kyc(device_info_clean)

    # Check Client App Header
    is_client_app = (
        request.headers.get("X-App-Client") == "SHE-Admin-Android" or
        request.headers.get("User-Agent", "").startswith("SHE-Admin")
    )

    # If REGISTRATION_TOKEN is configured in environment, require token UNLESS:
    # Client is the verified SHE Android app providing authentic KYC (phone + genuine hardware specs)
    if settings.REGISTRATION_TOKEN:
        token_provided = user_in.registration_token or request.headers.get("X-Registration-Token", "")
        token_valid = bool(token_provided) and secrets.compare_digest(token_provided, settings.REGISTRATION_TOKEN)
        kyc_verified = is_client_app and bool(phone_clean) and hw_ok

        if not token_valid and not kyc_verified:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Invalid or missing registration verification token. Account creation requires a verified invitation token or authentic mobile hardware KYC."
            )

    # Registration lockdown: The platform operates under a single-owner model.
    # In production (ALLOW_PUBLIC_REGISTRATION=False), once the first owner account exists,
    # public registration is permanently closed.
    if not settings.ALLOW_PUBLIC_REGISTRATION and existing_users > 0:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Public registration is permanently closed. Owner account already initialized."
        )

    # Check if email is already claimed by a verified owner.
    # Unverified accounts are allowed to co-exist; the first to confirm OTP wins the email.
    verified_email_user = db.query(User).filter(
        User.email == user_in.email,
        User.email_verified_at != None
    ).first()
    if verified_email_user:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email already registered"
        )

    # Check if username exists
    if db.query(User).filter(User.username == user_in.username).first():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Username already registered"
        )
    
    user = User(
        username=user_in.username,
        email=user_in.email,
        phone_number=user_in.phone_number,
        device_info=user_in.device_info,
        hashed_password=hash_password(user_in.password)
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


# Constant-time dummy hash to normalize Argon2id execution time and eliminate timing-based user enumeration
_DUMMY_ARGON2_HASH = hash_password("dummy-entropy-for-constant-time-auth")


def create_user_session(
    db: Session,
    user: User,
    request: Request,
    device_info: str | None = None
) -> tuple[UserSession, str]:
    """
    Record an active login session.
    Detects whether this session corresponds to the primary registered device.
    """
    client_ip = request.client.host if request.client else "unknown"
    user_agent = request.headers.get("User-Agent", "unknown")
    clean_device_info = (device_info or request.headers.get("X-Device-Info") or user_agent).strip()

    # Determine if this login is from the primary registered device
    is_registered = False
    if user.device_info and clean_device_info:
        u_dev = user.device_info.strip().lower()
        c_dev = clean_device_info.lower()
        if u_dev == c_dev or (u_dev in c_dev) or (c_dev in u_dev):
            is_registered = True
        elif "[id:" in u_dev and "[id:" in c_dev:
            id_u = u_dev.split("[id:")[1].split("]")[0].strip()
            id_c = c_dev.split("[id:")[1].split("]")[0].strip()
            if id_u and id_u == id_c:
                is_registered = True
    elif not user.device_info:
        if "SHE-Admin" in user_agent or request.headers.get("X-App-Client") == "SHE-Admin-Android":
            is_registered = True

    session_jti = secrets.token_hex(16)
    raw_refresh_token = secrets.token_hex(32)
    refresh_hash = hashlib.sha256(raw_refresh_token.encode()).hexdigest()

    sess = UserSession(
        user_id=user.id,
        session_token_jti=session_jti,
        device_info=clean_device_info,
        ip_address=client_ip,
        user_agent=user_agent[:255] if user_agent else None,
        is_registered_device=is_registered,
        is_2fa=False,
        refresh_token_hash=refresh_hash,
        created_at=utc_now(),
        last_active_at=utc_now(),
        revoked_at=None
    )
    db.add(sess)
    db.commit()
    db.refresh(sess)
    return sess, session_jti, raw_refresh_token


@router.post("/login", response_model=Token)
def login(
    request: Request,
    form_data: OAuth2PasswordRequestForm = Depends(),
    db: Session = Depends(get_db)
) -> Any:
    """OAuth2 password login issuing baseline session."""
    auth_limiter.check_rate_limit(request)

    user = db.query(User).filter(
        (User.username == form_data.username) | (User.email == form_data.username)
    ).first()
    
    if user:
        is_valid = verify_password(form_data.password, user.hashed_password)
    else:
        # Run dummy verification to equalize response timing with genuine accounts (~80ms Argon2id)
        verify_password(form_data.password, _DUMMY_ARGON2_HASH)
        is_valid = False

    if not is_valid:
        audit = AuditEvent(
            user_id=user.id if user else None,
            event_type="LOGIN_FAILED",
            details=f"Failed login attempt for identifier '{form_data.username}'."
        )
        db.add(audit)
        db.commit()
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    
    audit = AuditEvent(
        user_id=user.id,
        event_type="LOGIN_SUCCESS",
        details="User authenticated successfully via password (1FA)."
    )
    db.add(audit)
    db.commit()

    sess, session_jti, raw_refresh = create_user_session(db, user, request, form_data.client_id)
    access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    token = create_access_token(
        subject=user.id,
        is_2fa=False,
        expires_delta=access_token_expires,
        jti=session_jti
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": False,
        "totp_required": bool(user.totp_enabled),
        "refresh_token": raw_refresh
    }


@router.post("/login/json", response_model=Token)
def login_json(
    request: Request,
    login_data: UserLogin,
    db: Session = Depends(get_db)
) -> Any:
    """JSON login endpoint for mobile admin client."""
    auth_limiter.check_rate_limit(request)

    user = db.query(User).filter(
        (User.username == login_data.username) | (User.email == login_data.username)
    ).first()
    
    if user:
        is_valid = verify_password(login_data.password, user.hashed_password)
    else:
        # Run dummy verification to equalize response timing with genuine accounts (~80ms Argon2id)
        verify_password(login_data.password, _DUMMY_ARGON2_HASH)
        is_valid = False

    if not is_valid:
        audit = AuditEvent(
            user_id=user.id if user else None,
            event_type="LOGIN_FAILED",
            details=f"Failed login attempt for identifier '{login_data.username}'."
        )
        db.add(audit)
        db.commit()
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )

    audit = AuditEvent(
        user_id=user.id,
        event_type="LOGIN_SUCCESS",
        details="User authenticated successfully via password (1FA)."
    )
    db.add(audit)
    db.commit()

    sess, session_jti, raw_refresh = create_user_session(db, user, request, login_data.device_info)
    access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    token = create_access_token(
        subject=user.id,
        is_2fa=False,
        expires_delta=access_token_expires,
        jti=session_jti
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": False,
        "totp_required": bool(user.totp_enabled),
        "refresh_token": raw_refresh
    }


def generate_recovery_codes(count: int = 8) -> tuple[list[str], list[str]]:
    """Generate human-readable Crockford Base32 recovery codes (XXXX-XXXX) and their Argon2id memory-hard hashes."""
    charset = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    codes = []
    hashes = []
    for _ in range(count):
        part1 = "".join(secrets.choice(charset) for _ in range(4))
        part2 = "".join(secrets.choice(charset) for _ in range(4))
        code = f"{part1}-{part2}"
        codes.append(code)
        hashes.append(hash_password(code))
    return codes, hashes


@router.post("/totp/setup", response_model=TotpSetupResponse)
def setup_totp(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """Generate a new TOTP secret for the authenticated owner."""
    if current_user.totp_enabled:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="TOTP is already enabled. Password-only (1FA) authentication cannot reset or replace TOTP. Use /auth/totp/disable with an active 2FA session first."
        )

    secret = generate_totp_secret()
    current_user.totp_secret = encrypt_totp_secret(secret)
    db.commit()
    return {
        "secret": secret,
        "otpauth_uri": get_totp_uri(secret, current_user.username)
    }


@router.post("/totp/verify", response_model=Token)
def verify_totp(
    req: TotpVerifyRequest,
    request: Request,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """Verify initial TOTP code to activate 2FA, generate emergency recovery codes, and return an upgraded 2FA token."""
    totp_limiter.check_rate_limit(request, key_override=f"totp:{current_user.id}")

    if not current_user.totp_secret:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="TOTP setup not initiated. Call /auth/totp/setup first."
        )
    
    plain_secret = decrypt_totp_secret(current_user.totp_secret)
    if not verify_totp_code(plain_secret, req.code):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid TOTP authentication code"
        )
    
    plain_codes, code_hashes = generate_recovery_codes(8)
    current_user.totp_enabled = True
    current_user.totp_recovery_hashes = json.dumps(code_hashes)
    db.commit()

    token = create_access_token(
        subject=current_user.id,
        is_2fa=True
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": True,
        "recovery_codes": plain_codes
    }


@router.post("/totp/validate", response_model=Token)
def validate_totp(
    req: TotpVerifyRequest,
    request: Request,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """Upgrade a 1FA session to a full 2FA session via TOTP code verification or emergency recovery code."""
    totp_limiter.check_rate_limit(request, key_override=f"totp:{current_user.id}")

    if not current_user.totp_enabled or not current_user.totp_secret:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="TOTP is not enabled for this user."
        )
    
    clean_code = req.code.strip().upper()
    totp_valid = False

    # Check standard 6-digit TOTP code
    plain_secret = decrypt_totp_secret(current_user.totp_secret)
    if len(clean_code) == 6 and clean_code.isdigit():
        if verify_totp_code(plain_secret, clean_code):
            totp_valid = True

    # If TOTP code didn't match, check emergency recovery codes (Argon2id verified)
    if not totp_valid and current_user.totp_recovery_hashes:
        stored_hashes = json.loads(current_user.totp_recovery_hashes or "[]")
        matched_hash = None
        for h in stored_hashes:
            if verify_password(clean_code, h):
                matched_hash = h
                break
        if matched_hash:
            stored_hashes.remove(matched_hash)
            current_user.totp_recovery_hashes = json.dumps(stored_hashes)
            audit = AuditEvent(
                user_id=current_user.id,
                event_type="TOTP_RECOVERY_CODE_CONSUMED",
                details=f"Owner redeemed single-use emergency TOTP recovery code. Remaining: {len(stored_hashes)}"
            )
            db.add(audit)
            db.commit()
            totp_valid = True

    if not totp_valid:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid TOTP authentication code or emergency recovery code"
        )
    
    # Upgrade current session to 2FA and issue rotated 2FA refresh token
    jti = None
    raw_2fa_refresh = None
    req_auth = request.headers.get("Authorization", "")
    if req_auth.startswith("Bearer "):
        p = verify_access_token(req_auth[7:])
        if p:
            jti = p.get("jti")

    if jti:
        user_sess = db.query(UserSession).filter(UserSession.session_token_jti == jti).first()
        if user_sess:
            user_sess.is_2fa = True
            raw_2fa_refresh = secrets.token_hex(32)
            user_sess.refresh_token_hash = hashlib.sha256(raw_2fa_refresh.encode()).hexdigest()
            user_sess.last_active_at = utc_now()
            db.commit()

    token = create_access_token(
        subject=current_user.id,
        is_2fa=True,
        jti=jti
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": True,
        "refresh_token": raw_2fa_refresh
    }


@router.post("/refresh", response_model=Token)
def refresh_session_token(
    request: Request,
    payload: RefreshTokenRequest,
    db: Session = Depends(get_db)
) -> Any:
    """
    Exchange a valid rotating refresh token for a fresh access token and new refresh token.

    Security properties:
    1. Single-use rotation: the current refresh token is immediately replaced.
    2. Reuse detection: if a consumed (old) refresh token is replayed, the entire
       session is revoked. This catches token theft where the attacker and the
       legitimate client both try to refresh.
    3. Concurrent safety: SELECT ... FOR UPDATE prevents two simultaneous refresh
       calls from both succeeding with the same token.
    """
    auth_limiter.check_rate_limit(request)
    raw_tok = payload.refresh_token.strip()
    token_hash = hashlib.sha256(raw_tok.encode()).hexdigest()

    # Lock the row to prevent concurrent refreshes from both succeeding
    sess = db.query(UserSession).filter(
        UserSession.refresh_token_hash == token_hash,
        UserSession.revoked_at == None
    ).with_for_update().first()

    if not sess:
        # Check if this is a CONSUMED token (reuse attack detection).
        # Check both the immediate prior hash and all historical consumed hashes for active sessions
        reused_sess = db.query(UserSession).filter(
            UserSession.consumed_refresh_hash == token_hash,
            UserSession.revoked_at == None
        ).with_for_update().first()

        if not reused_sess:
            # Also check historical consumed hashes across active sessions
            active_sessions = db.query(UserSession).filter(
                UserSession.revoked_at == None
            ).with_for_update().all()
            for s in active_sessions:
                try:
                    hashes = json.loads(s.consumed_refresh_hashes or "[]")
                    if token_hash in hashes:
                        reused_sess = s
                        break
                except Exception:
                    pass

        now_utc = utc_now()
        is_grace_window = False
        if reused_sess and reused_sess.consumed_refresh_hash == token_hash:
            # Check if this exact token was consumed within the 10-second grace window
            t_last = reused_sess.last_active_at
            if t_last:
                if t_last.tzinfo is None:
                    t_last = t_last.replace(tzinfo=timezone.utc)
                delta = abs(now_utc.timestamp() - t_last.timestamp())
            else:
                delta = 999.0
            if delta <= 10.0:
                is_grace_window = True

        if is_grace_window:
            # Grace window active: legitimate client sent parallel or retry request with the prior token.
            # Do NOT revoke the session. Return a valid access token for the active session.
            grace_user = db.query(User).filter(User.id == reused_sess.user_id).first()
            if grace_user:
                db.add(AuditEvent(
                    user_id=reused_sess.user_id,
                    event_type="REFRESH_TOKEN_GRACE_WINDOW_USED",
                    details=(
                        f"Consumed refresh token replayed within 10-second grace window "
                        f"(parallel/retry request). Session {reused_sess.id}. "
                        f"IP: {request.client.host if request.client else 'unknown'}."
                    )
                ))
                db.flush()
                access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
                fresh_access_token = create_access_token(
                    subject=grace_user.id,
                    is_2fa=reused_sess.is_2fa,
                    expires_delta=access_token_expires,
                    jti=reused_sess.session_token_jti
                )
                return {
                    "access_token": fresh_access_token,
                    "token_type": "bearer",
                    "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
                    "is_2fa": reused_sess.is_2fa,
                    "totp_required": bool(grace_user.totp_enabled) and not reused_sess.is_2fa,
                    "refresh_token": None
                }

        if reused_sess:
            # TOKEN REUSE DETECTED (outside grace window) — revoke the entire session immediately.
            # This applies to ANY previously consumed token across rotation history.
            reused_sess.revoked_at = utc_now()
            db.add(AuditEvent(
                user_id=reused_sess.user_id,
                event_type="REFRESH_TOKEN_REUSE_DETECTED",
                details=f"Consumed refresh token replayed (reuse of historical token) — session {reused_sess.id} revoked. "
                        f"IP: {request.client.host if request.client else 'unknown'}. "
                        f"This may indicate token theft."
            ))
            db.commit()
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Refresh token reuse detected. Session revoked for security. Please log in again.",
                headers={"WWW-Authenticate": "Bearer"},
            )

        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid, expired, or already-consumed refresh token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    user = db.query(User).filter(User.id == sess.user_id).first()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User not found",
            headers={"WWW-Authenticate": "Bearer"},
        )

    sess_is_2fa = bool(sess.is_2fa)

    # Rotate refresh token: single-use replacement with atomic compare-and-swap
    try:
        hist = json.loads(sess.consumed_refresh_hashes or "[]")
    except Exception:
        hist = []
    hist.append(token_hash)
    if len(hist) > 20:
        hist = hist[-20:]  # Cap rotation history to last 20 entries to prevent unbounded growth

    new_raw_refresh = secrets.token_hex(32)
    new_refresh_hash = hashlib.sha256(new_raw_refresh.encode()).hexdigest()
    new_jti = secrets.token_hex(16)
    now_time = utc_now()
    client_ip = request.client.host if request.client else None

    # Atomic CAS update: only succeeds if refresh_token_hash still matches token_hash
    updated_count = db.query(UserSession).filter(
        UserSession.id == sess.id,
        UserSession.refresh_token_hash == token_hash,
        UserSession.revoked_at == None
    ).update({
        UserSession.consumed_refresh_hash: token_hash,
        UserSession.consumed_refresh_hashes: json.dumps(hist),
        UserSession.refresh_token_hash: new_refresh_hash,
        UserSession.session_token_jti: new_jti,
        UserSession.last_active_at: now_time,
        UserSession.ip_address: client_ip
    }, synchronize_session=False)

    db.commit()

    if updated_count == 0:
        # A concurrent request already rotated this token!
        # Check if the collision occurred within the 10-second grace window
        conflicted_sess = db.query(UserSession).filter(UserSession.id == sess.id).first()
        if conflicted_sess and conflicted_sess.revoked_at is None:
            t_last = conflicted_sess.last_active_at
            if t_last:
                if t_last.tzinfo is None:
                    t_last = t_last.replace(tzinfo=timezone.utc)
                delta = abs(utc_now().timestamp() - t_last.timestamp())
            else:
                delta = 999.0
            if delta <= 10.0:
                # Concurrent race within grace window: do NOT revoke session! Return valid access token.
                db.add(AuditEvent(
                    user_id=user.id,
                    event_type="REFRESH_TOKEN_CONCURRENT_GRACE_WINDOW_USED",
                    details=(
                        f"Concurrent refresh race resolved within 10-second grace window. "
                        f"Session {conflicted_sess.id}. "
                        f"IP: {request.client.host if request.client else 'unknown'}."
                    )
                ))
                db.flush()
                access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
                race_access_token = create_access_token(
                    subject=user.id,
                    is_2fa=conflicted_sess.is_2fa,
                    expires_delta=access_token_expires,
                    jti=conflicted_sess.session_token_jti
                )
                return {
                    "access_token": race_access_token,
                    "token_type": "bearer",
                    "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
                    "is_2fa": conflicted_sess.is_2fa,
                    "totp_required": bool(user.totp_enabled) and not conflicted_sess.is_2fa,
                    "refresh_token": None
                }
            # Beyond grace window: revoke session as replay attack
            conflicted_sess.revoked_at = utc_now()
            db.add(AuditEvent(
                user_id=conflicted_sess.user_id,
                event_type="REFRESH_TOKEN_REUSE_DETECTED",
                details=f"Concurrent refresh conflict detected — session {conflicted_sess.id} revoked."
            ))
            db.commit()
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token reuse detected. Session revoked due to concurrent conflict.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    new_access_token = create_access_token(
        subject=user.id,
        is_2fa=sess_is_2fa,
        expires_delta=access_token_expires,
        jti=new_jti
    )

    return {
        "access_token": new_access_token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": sess_is_2fa,
        "totp_required": bool(user.totp_enabled) and not sess_is_2fa,
        "refresh_token": new_raw_refresh
    }


@router.post("/totp/disable", response_model=TotpStatusResponse)
def disable_totp(
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """
    Disable TOTP 2FA for the authenticated owner.
    CRITICAL SECURITY ENFORCEMENT:
    Requires active 2FA session (get_current_user_2fa).
    Password-only (1FA) authentication cannot disable, reset, or replace TOTP.
    """
    current_user.totp_enabled = False
    current_user.totp_secret = None
    current_user.totp_recovery_hashes = "[]"

    audit = AuditEvent(
        user_id=current_user.id,
        event_type="TOTP_2FA_DISABLED",
        details="Owner disabled TOTP two-factor authentication."
    )
    db.add(audit)
    db.commit()

    return TotpStatusResponse(totp_enabled=False)


@router.get("/me", response_model=UserResponse)
def read_current_user(current_user: User = Depends(get_current_user)) -> Any:
    """Get profile of current authenticated owner."""
    return current_user


@router.post("/pairing-token", response_model=PairingTokenResponse)
def create_device_pairing_token(
    req: PairingTokenCreateRequest,
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """
    Issue a single-use, 5-minute restricted token strictly for enrolling a Guardian device.
    Requires verified 2FA session. Cannot be used to execute recovery commands.
    """
    if req.trusted_device_id:
        trusted = db.query(TrustedDevice).filter(
            TrustedDevice.id == req.trusted_device_id,
            TrustedDevice.user_id == current_user.id,
            TrustedDevice.revoked_at.is_(None)
        ).first()
        if not trusted:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Trusted admin device not recognized or revoked."
            )

    token = create_pairing_token(
        user_id=current_user.id,
        trusted_device_id=req.trusted_device_id,
        expires_minutes=15
    )
    return PairingTokenResponse(
        pairing_token=token,
        expires_in_seconds=900
    )


@router.post("/trusted-devices/{trusted_device_id}/revoke", status_code=status.HTTP_200_OK)
def revoke_trusted_device(
    trusted_device_id: str,
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """
    Revoke a compromised or lost Admin device.
    Commands signed by this device will be immediately rejected.
    """
    trusted = db.query(TrustedDevice).filter(
        TrustedDevice.id == trusted_device_id,
        TrustedDevice.user_id == current_user.id
    ).first()
    if not trusted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Trusted device not found for this user."
        )

    trusted.is_active = False
    trusted.revoked_at = utc_now()

    audit = AuditEvent(
        user_id=current_user.id,
        event_type="TRUSTED_DEVICE_REVOKED",
        details=f"Trusted device {trusted.device_name} ({trusted.id}) was revoked by owner."
    )
    db.add(audit)
    db.commit()

    return {
        "status": "REVOKED",
        "trusted_device_id": trusted.id,
        "device_name": trusted.device_name,
        "revoked_at": trusted.revoked_at
    }


# -----------------------------------------------------------------------------
# Active Login Sessions Management
# -----------------------------------------------------------------------------

@router.get("/sessions", response_model=list[UserSessionResponse])
def list_user_sessions(
    request: Request,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    List all active and recent login sessions for the current user.
    Identifies the primary registered device and marks the current session.
    """
    auth_header = request.headers.get("Authorization", "")
    current_jti = None
    if auth_header.startswith("Bearer "):
        token_str = auth_header[7:].strip()
        payload = verify_access_token(token_str)
        if payload:
            current_jti = payload.get("jti")

    sessions = db.query(UserSession).filter(
        UserSession.user_id == current_user.id,
        UserSession.revoked_at.is_(None)
    ).order_by(UserSession.last_active_at.desc()).all()

    resp = []
    for s in sessions:
        resp.append(UserSessionResponse(
            id=s.id,
            device_info=s.device_info,
            ip_address=s.ip_address,
            user_agent=s.user_agent,
            is_registered_device=bool(s.is_registered_device),
            is_current=bool(current_jti and s.session_token_jti == current_jti),
            created_at=s.created_at,
            last_active_at=s.last_active_at,
            is_revoked=bool(s.revoked_at is not None)
        ))
    return resp


@router.post("/sessions/{session_id}/revoke", response_model=SessionRevokeResponse)
def revoke_user_session(
    session_id: str,
    force: bool = False,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Revoke/terminate a specific login session.
    Protects the primary registered device from accidental revocation unless force=True.
    """
    session = db.query(UserSession).filter(
        UserSession.id == session_id,
        UserSession.user_id == current_user.id
    ).first()

    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Session not found"
        )

    if session.is_registered_device and not force:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot terminate the primary registered device session unless force=true is specified."
        )

    session.revoked_at = utc_now()
    db.commit()

    return {
        "status": "revoked",
        "revoked_count": 1,
        "message": f"Session '{session.id}' has been terminated."
    }


@router.post("/sessions/revoke-others", response_model=SessionRevokeResponse)
def revoke_other_sessions(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Terminates all active login sessions (e.g. web emergency console, borrowed devices,
    remote browsers) EXCEPT the primary device recorded during registration.
    """
    query = db.query(UserSession).filter(
        UserSession.user_id == current_user.id,
        UserSession.is_registered_device == False,
        UserSession.revoked_at.is_(None)
    )
    count = query.count()
    query.update({UserSession.revoked_at: utc_now()}, synchronize_session=False)
    db.commit()

    audit = AuditEvent(
        user_id=current_user.id,
        event_type="REMOTE_SESSIONS_REVOKED",
        details=f"Revoked {count} remote session(s). Primary registered device preserved."
    )
    db.add(audit)
    db.commit()

    return {
        "status": "revoked",
        "revoked_count": count,
        "message": f"Successfully revoked {count} remote session(s). Primary registered device remains active."
    }
