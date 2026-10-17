from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.db.models import User
from app.core.security import verify_access_token, verify_pairing_token, claim_pairing_token_jti

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
    
    user = db.query(User).filter(User.id == payload["sub"]).first()
    if user is None:
        raise credentials_exception
    return user


def get_current_user_2fa(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db)
) -> User:
    """
    Strict dependency enforcing verified Two-Factor Authentication (is_2fa=True).
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
    if not user.totp_enabled:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Two-factor authentication (2FA/TOTP) must be enrolled and verified before accessing privileged endpoints",
        )
    return user


def get_pairing_token_user(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db)
) -> tuple[User, dict]:
    """
    Strict dependency enforcing a valid, unconsumed, short-lived device pairing token.
    Atomically claims JTI upon verification.
    """
    payload = verify_pairing_token(token)
    if not payload or "sub" not in payload:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid, expired, or already-consumed pairing token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    
    # Claim JTI
    jti = payload.get("jti")
    if not jti or not claim_pairing_token_jti(jti):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Pairing token has already been consumed",
            headers={"WWW-Authenticate": "Bearer"},
        )

    user = db.query(User).filter(User.id == payload["sub"]).first()
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User not found")
    return user, payload
