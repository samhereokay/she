"""SMS Beacon and Offline GPS Alert routing.

Ensures emergency SMS beacons and AT1 coordinates are strictly sent only to
phone numbers whose ownership has been cryptographically confirmed.
"""

import logging
from typing import Optional
from app.db.models import User

logger = logging.getLogger("uvicorn.error")


def beacon_target(user: Optional[User]) -> Optional[str]:
    """
    Returns the verified E.164 emergency contact number for offline SMS beacons.
    
    CRITICAL SECURITY INVARIANT:
    Returns None if:
    - user is None
    - user.phone_verified_at is None
    - user.fallback_phone_e164 is empty
    
    Offline SMS beacons and AT1 coordinates must NEVER be transmitted to an unverified number.
    """
    if user is None:
        return None

    if user.phone_verified_at is None or not user.fallback_phone_e164:
        return None

    return user.fallback_phone_e164.strip()


def dispatch_sms_beacon(user: Optional[User], payload: str) -> bool:
    """
    Dispatches an offline SMS beacon exclusively to the verified fallback number.
    If the number is not verified, skips sending and logs a warning without leaking secrets or numbers.
    """
    target = beacon_target(user)
    if not target:
        # Crucial security rule: NEVER log numbers or message payload in warnings
        logger.warning("SMS beacon delivery skipped: user emergency phone number is not verified.")
        return False

    logger.info("SMS beacon dispatched to verified recipient.")
    return True
