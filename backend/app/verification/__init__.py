"""Verification module for challenge-response email and phone ownership validation."""
from app.verification.engine import issue, confirm
from app.verification.mailer import send_verification_email

__all__ = ["issue", "confirm", "send_verification_email"]
