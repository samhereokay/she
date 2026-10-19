from datetime import timedelta
import hashlib
import json
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
from app.db.session import get_db
from app.db.models import User, TrustedDevice, AuditEvent, utc_now
from app.schemas.auth import (
    UserCreate,
    UserResponse,
    Token,
    UserLogin,
    TotpSetupResponse,
    TotpVerifyRequest,
    TotpStatusResponse
)
from app.schemas.device import PairingTokenCreateRequest, PairingTokenResponse
from app.api.deps import get_current_user, get_current_user_2fa
from app.core.security import create_pairing_token

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

    # If REGISTRATION_TOKEN is configured in environment, verify the user's registration token
    if settings.REGISTRATION_TOKEN:
        token_provided = user_in.registration_token or request.headers.get("X-Registration-Token", "")
        if not secrets.compare_digest(token_provided, settings.REGISTRATION_TOKEN):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Invalid or missing registration verification token. Account creation requires a verified invitation token."
            )

    # Registration lockdown: The platform operates under a single-owner model.
    # In production (ALLOW_PUBLIC_REGISTRATION=False), once the first owner account exists,
    # public registration is permanently closed.
    if not settings.ALLOW_PUBLIC_REGISTRATION and existing_users > 0:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Public registration is permanently closed. Owner account already initialized."
        )

    # Check if username or email exists
    if db.query(User).filter(User.username == user_in.username).first():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Username already registered"
        )
    if db.query(User).filter(User.email == user_in.email).first():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email already registered"
        )
    
    user = User(
        username=user_in.username,
        email=user_in.email,
        hashed_password=hash_password(user_in.password)
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


# Constant-time dummy hash to normalize Argon2id execution time and eliminate timing-based user enumeration
_DUMMY_ARGON2_HASH = hash_password("dummy-entropy-for-constant-time-auth")


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

    access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    # Password authentication ALWAYS issues a 1FA token (is_2fa=False).
    # Two-factor authentication (TOTP) is mandatory for privileged commands/vault/pairing.
    token = create_access_token(
        subject=user.id,
        is_2fa=False,
        expires_delta=access_token_expires
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": False
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

    access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    # Password authentication ALWAYS issues a 1FA token (is_2fa=False).
    token = create_access_token(
        subject=user.id,
        is_2fa=False,
        expires_delta=access_token_expires
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": False
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
    
    token = create_access_token(
        subject=current_user.id,
        is_2fa=True
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": True
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
