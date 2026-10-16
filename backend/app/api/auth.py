from datetime import timedelta
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
from app.db.models import User
from app.schemas.auth import (
    UserCreate,
    UserResponse,
    Token,
    UserLogin,
    TotpSetupResponse,
    TotpVerifyRequest,
    TotpStatusResponse
)
from app.api.deps import get_current_user

router = APIRouter(prefix="/auth", tags=["Authentication"])


@router.post("/register", response_model=UserResponse, status_code=status.HTTP_201_CREATED)
def register(request: Request, user_in: UserCreate, db: Session = Depends(get_db)) -> Any:
    """Register a new owner admin user with lockdown check and rate limiting."""
    auth_limiter.check_rate_limit(request)

    if not settings.ALLOW_PUBLIC_REGISTRATION:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Public registration is disabled. Administrator invitation required."
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
    
    if not user or not verify_password(form_data.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    
    access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    # If TOTP is enabled, issue 1FA token (cannot run commands without 2FA step)
    is_2fa = not user.totp_enabled
    token = create_access_token(
        subject=user.id,
        is_2fa=is_2fa,
        expires_delta=access_token_expires
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": is_2fa
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
    
    if not user or not verify_password(login_data.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    
    access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    is_2fa = not user.totp_enabled
    token = create_access_token(
        subject=user.id,
        is_2fa=is_2fa,
        expires_delta=access_token_expires
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in_minutes": settings.ACCESS_TOKEN_EXPIRE_MINUTES,
        "is_2fa": is_2fa
    }


@router.post("/totp/setup", response_model=TotpSetupResponse)
def setup_totp(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """Generate a new TOTP secret for the authenticated owner."""
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
    """Verify initial TOTP code to activate 2FA and return an upgraded 2FA token."""
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
    
    current_user.totp_enabled = True
    db.commit()

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


@router.post("/totp/validate", response_model=Token)
def validate_totp(
    req: TotpVerifyRequest,
    request: Request,
    current_user: User = Depends(get_current_user)
) -> Any:
    """Upgrade a 1FA session to a full 2FA session via TOTP code verification."""
    totp_limiter.check_rate_limit(request, key_override=f"totp:{current_user.id}")

    if not current_user.totp_enabled or not current_user.totp_secret:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="TOTP is not enabled for this user."
        )
    
    plain_secret = decrypt_totp_secret(current_user.totp_secret)
    if not verify_totp_code(plain_secret, req.code):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid TOTP authentication code"
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


@router.get("/me", response_model=UserResponse)
def read_current_user(current_user: User = Depends(get_current_user)) -> Any:
    """Get profile of current authenticated owner."""
    return current_user
