from datetime import datetime, timezone
from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from sqlalchemy.orm import Session
import logging
from app.db.session import get_db
from app.db.models import User, UserSession, utc_now
from app.core.security import verify_access_token, verify_pairing_token, claim_pairing_token_jti

logger = logging.getLogger("uvicorn.error")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/auth/login")


def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db)
) -> User:
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    payload = verify_access_token(token)
    if not payload or "sub" not in payload:
        raise credentials_exception
    
    if payload.get("scope") == "device_pairing":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Single-use device pairing token cannot be used for user or admin operations"
        )
    
    jti = payload.get("jti")
    if jti:
        user_sess = db.query(UserSession).filter(UserSession.session_token_jti == jti).first()
        if user_sess and user_sess.revoked_at is not None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="session_revoked: Login session has been terminated.",
                headers={"WWW-Authenticate": "Bearer"},
            )
        if user_sess:
            user_sess.last_active_at = utc_now()
            db.commit()

    user = db.query(User).filter(User.id == payload["sub"]).first()
    if user is None:
        raise credentials_exception
    return user


def get_verified_user(
    current_user: User = Depends(get_current_user)
) -> User:
    """
    Strict dependency enforcing verified ownership of the account email address.
    Unverified accounts cannot perform device operations, commands, or access vault.
    """
    if current_user.email_verified_at is None and current_user.phone_verified_at is None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="email_not_verified"
        )
    return current_user


def get_current_user_2fa(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db)
) -> User:
    """
    Strict dependency enforcing verified Two-Factor Authentication (is_2fa=True)
    and verified account ownership (email_verified_at is not None).
    Mandated before any recovery command endpoint is permitted to execute.
    """
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    two_factor_required_exception = HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="Two-factor authentication (2FA/TOTP) required before issuing commands",
    )
    payload = verify_access_token(token)
    if not payload or "sub" not in payload:
        raise credentials_exception
    
    if payload.get("scope") == "device_pairing":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Single-use device pairing token cannot be used for user or admin operations"
        )
    
    if not payload.get("is_2fa", False):
        raise two_factor_required_exception

    user = db.query(User).filter(User.id == payload["sub"]).first()
    if user is None:
        raise credentials_exception
    if user.email_verified_at is None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="email_not_verified"
        )
    if not user.totp_enabled:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Two-factor authentication (2FA/TOTP) must be enrolled and verified before accessing privileged endpoints",
        )
    user._totp_verified_at = payload.get("totp_verified_at") or payload.get("iat")
    return user


def get_pairing_token_user(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db)
) -> tuple[User, dict]:
    """
    Strict dependency enforcing a valid, unconsumed, short-lived device pairing token.
    Atomically claims JTI upon verification.
    """
    clean_token = token.strip().strip('"').strip("'")

    # 1. Check database-backed PairingSession first (supports 5-minute single-use pairing sessions and short codes)
    from app.db.models import PairingSession
    pairing_sess = db.query(PairingSession).filter(
        (PairingSession.session_token == clean_token) | (PairingSession.short_code == clean_token)
    ).first()
    if pairing_sess:
        if pairing_sess.consumed_at is not None:
            logger.warning(f"get_pairing_token_user: Pairing session {pairing_sess.id} already consumed")
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Pairing session has already been consumed",
                headers={"WWW-Authenticate": "Bearer"},
            )
        now = datetime.now(timezone.utc)
        expires = pairing_sess.expires_at
        if expires.tzinfo is None:
            expires = expires.replace(tzinfo=timezone.utc)
        if expires < now:
            logger.warning(f"get_pairing_token_user: Pairing session {pairing_sess.id} expired at {expires}")
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Pairing session has expired",
                headers={"WWW-Authenticate": "Bearer"},
            )
        pairing_sess.consumed_at = now
        db.commit()
        return pairing_sess.user, {"sub": pairing_sess.user_id, "session_id": pairing_sess.id, "scope": "device_pairing"}

    # 2. Fall back to JWT pairing token
    payload = verify_pairing_token(clean_token)
    if not payload or "sub" not in payload:
        logger.warning(f"get_pairing_token_user: Invalid, expired, or already-consumed token (token_len={len(clean_token)})")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid, expired, or already-consumed pairing token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    
    # Claim JTI
    jti = payload.get("jti")
    if not jti or not claim_pairing_token_jti(jti):
        logger.warning(f"get_pairing_token_user: JTI {jti} already claimed or missing")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Pairing token has already been consumed",
            headers={"WWW-Authenticate": "Bearer"},
        )

    user = db.query(User).filter(User.id == payload["sub"]).first()
    if user is None:
        logger.warning(f"get_pairing_token_user: User ID {payload.get('sub')} not found")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User not found")
    return user, payload
