from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.db.models import User
from app.core.security import verify_access_token

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
    
    if not payload.get("is_2fa", False):
        raise two_factor_required_exception

    user = db.query(User).filter(User.id == payload["sub"]).first()
    if user is None:
        raise credentials_exception
    return user
