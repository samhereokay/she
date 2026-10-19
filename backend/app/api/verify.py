"""Zero-trust ownership challenge endpoints (Email OTP & SMS Phone Verification)."""

import logging
from typing import Any
from fastapi import APIRouter, Depends, HTTPException, status, BackgroundTasks
from sqlalchemy.orm import Session

from app.api.deps import get_current_user
from app.core.kyc_validator import validate_email_kyc
from app.db.session import get_db
from app.db.models import User, AuditEvent, utc_now
from app.schemas.auth import EmailSendRequest, EmailConfirmRequest, EmailVerifyResponse
from app.verification.engine import issue, confirm
from app.verification.mailer import send_verification_email

logger = logging.getLogger("uvicorn.error")

router = APIRouter(prefix="/verify", tags=["Verification"])


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
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=err_msg
        )

    # Check if target email belongs to another registered account
    existing = db.query(User).filter(User.email == target_email, User.id != current_user.id).first()
    if existing:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email already registered by another account."
        )

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
