"""Cryptographic Challenge Verification Engine.

Manages zero-trust challenge creation, rate limits, cooldowns, and HMAC verification.
"""

import hashlib
import hmac
import secrets
from datetime import datetime, timedelta, timezone
from typing import Tuple
from fastapi import HTTPException, status
from sqlalchemy.orm import Session

from app.core.config import settings
from app.db.models import VerificationChallenge, generate_uuid, utc_now

CHALLENGE_TTL_MINUTES = 10
RESEND_COOLDOWN_SECONDS = 60
HOURLY_SEND_LIMIT = 5
MAX_ATTEMPTS_PER_CHALLENGE = 5


def compute_code_hash(challenge_id: str, code: str, secret: str) -> str:
    """Compute HMAC-SHA256 digest of challenge_id:code using server secret."""
    message = f"{challenge_id}:{code}".encode("utf-8")
    return hmac.new(secret.encode("utf-8"), message, hashlib.sha256).hexdigest()


def issue(
    db: Session,
    user_id: str,
    channel: str,
    target: str,
    now: datetime | None = None
) -> Tuple[VerificationChallenge, str]:
    """
    Issue a new 6-digit cryptographic verification challenge.
    
    Enforces:
    - 60-second resend cooldown per (user_id, channel, target)
    - 5 sends per hour rate limit per (user_id, channel, target)
    - Invalidation of previous open challenges for the same target
    - HMAC-SHA256 storage (plaintext code is never stored in DB)
    
    Returns:
        (VerificationChallenge, plaintext_code)
    """
    current_time = now or utc_now()
    clean_target = target.strip().lower() if channel == "email" else target.strip()
    secret = settings.VERIFY_SECRET or settings.SECRET_KEY

    # 1. Hourly send limit check (last 60 minutes)
    one_hour_ago = current_time - timedelta(hours=1)
    hourly_count = db.query(VerificationChallenge).filter(
        VerificationChallenge.user_id == user_id,
        VerificationChallenge.channel == channel,
        VerificationChallenge.target == clean_target,
        VerificationChallenge.created_at >= one_hour_ago
    ).count()

    if hourly_count >= HOURLY_SEND_LIMIT:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="hourly_rate_limit_exceeded: Maximum verification attempts reached for this hour. Please try again later."
        )

    # 2. Resend cooldown check (60 seconds)
    cooldown_threshold = current_time - timedelta(seconds=RESEND_COOLDOWN_SECONDS)
    recent_challenge = db.query(VerificationChallenge).filter(
        VerificationChallenge.user_id == user_id,
        VerificationChallenge.channel == channel,
        VerificationChallenge.target == clean_target,
        VerificationChallenge.created_at >= cooldown_threshold
    ).first()

    if recent_challenge:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="resend_cooldown_active: Please wait 60 seconds before requesting a new code."
        )

    # 3. Invalidate earlier open challenges for the same target
    open_challenges = db.query(VerificationChallenge).filter(
        VerificationChallenge.user_id == user_id,
        VerificationChallenge.channel == channel,
        VerificationChallenge.target == clean_target,
        VerificationChallenge.consumed_at.is_(None)
    ).all()

    for old_challenge in open_challenges:
        old_challenge.consumed_at = current_time

    # 4. Generate 6-digit code and HMAC-SHA256 digest
    challenge_id = generate_uuid()
    raw_code = f"{secrets.randbelow(10**6):06d}"
    code_hash = compute_code_hash(challenge_id, raw_code, secret)
    expires_at = current_time + timedelta(minutes=CHALLENGE_TTL_MINUTES)

    challenge = VerificationChallenge(
        id=challenge_id,
        user_id=user_id,
        channel=channel,
        target=clean_target,
        code_hash=code_hash,
        expires_at=expires_at,
        attempts=0,
        consumed_at=None,
        created_at=current_time
    )

    db.add(challenge)
    db.commit()
    db.refresh(challenge)

    return challenge, raw_code


def confirm(
    db: Session,
    user_id: str,
    channel: str,
    target: str,
    code: str,
    now: datetime | None = None
) -> VerificationChallenge:
    """
    Validate a user's verification code against active challenges.
    
    Enforces:
    - Replay prevention (cannot reuse consumed challenge)
    - Expiration check (TTL 10 min)
    - Attempt counter persisted BEFORE raising on invalid code
    - Max 5 attempts lock-out
    - Timing-safe constant-time HMAC comparison
    """
    current_time = now or utc_now()
    clean_target = target.strip().lower() if channel == "email" else target.strip()
    clean_code = (code or "").strip()
    secret = settings.VERIFY_SECRET or settings.SECRET_KEY

    # Find the most recent active challenge for this target
    challenge = db.query(VerificationChallenge).filter(
        VerificationChallenge.user_id == user_id,
        VerificationChallenge.channel == channel,
        VerificationChallenge.target == clean_target
    ).order_by(VerificationChallenge.created_at.desc()).first()

    if not challenge or challenge.consumed_at is not None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="challenge_invalid_or_consumed: No active verification challenge found or code already used."
        )

    # Check lock-out (max attempts)
    if challenge.attempts >= MAX_ATTEMPTS_PER_CHALLENGE:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="challenge_locked_too_many_attempts: Maximum incorrect attempts exceeded. Request a new code."
        )

    # Check expiration
    exp = challenge.expires_at
    if exp.tzinfo is None:
        exp = exp.replace(tzinfo=timezone.utc)
    if current_time > exp:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="challenge_expired: Verification code has expired. Request a new code."
        )

    # Persist attempt counter BEFORE raising on wrong code
    challenge.attempts += 1
    db.commit()

    # Constant-time HMAC comparison
    expected_hash = compute_code_hash(challenge.id, clean_code, secret)
    if not hmac.compare_digest(challenge.code_hash, expected_hash):
        if challenge.attempts >= MAX_ATTEMPTS_PER_CHALLENGE:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="challenge_locked_too_many_attempts: Maximum incorrect attempts exceeded. Request a new code."
            )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"invalid_verification_code: Incorrect code. {MAX_ATTEMPTS_PER_CHALLENGE - challenge.attempts} attempts remaining."
        )

    # Successfully verified: mark consumed
    challenge.consumed_at = current_time
    db.commit()
    db.refresh(challenge)

    return challenge
