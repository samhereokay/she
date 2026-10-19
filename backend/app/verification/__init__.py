"""Verification module for challenge-response email and phone ownership validation."""
from app.verification.engine import issue, confirm
from app.verification.mailer import send_verification_email
from app.verification.beacon import beacon_target, dispatch_sms_beacon

__all__ = ["issue", "confirm", "send_verification_email", "beacon_target", "dispatch_sms_beacon"]
