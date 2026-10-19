"""Test suite for Phase 3: SMS phone verification, beacon routing, Guardian SEND_VERIFY_SMS command."""

import json
import re
import pytest
from datetime import timedelta
from fastapi.testclient import TestClient

from app.db.models import (
    User, ProtectedDevice, DeviceCommand, VerificationChallenge, AuditEvent,
    utc_now, generate_uuid
)
from app.verification.beacon import beacon_target, dispatch_sms_beacon
from app.core.security import compute_truncated_hmac, create_canonical_command_bytes, hash_password, create_access_token
from app.main import app


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def verified_owner_user(db_session):
    """User with email already verified, ready for phone verification step."""
    user = User(
        id=generate_uuid(),
        username=f"phone_owner_{generate_uuid()[:8]}",
        email=f"owner_{generate_uuid()[:8]}@example.com",
        hashed_password=hash_password("ValidPass123!"),
        email_verified_at=utc_now()
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)

    token = create_access_token(
        subject=user.id,
        is_2fa=False,
        expires_delta=timedelta(minutes=30)
    )
    return token, user


# ---------------------------------------------------------------------------
# VALID phone numbers that pass all KYC checks (anti-dummy, format, mobile)
# These have sufficient digit entropy and no sequential runs of 6+ digits.
VALID_IN_PHONE = "+919538472610"
VALID_IN_PHONE_2 = "+918527419360"


# ===========================================================================
# 1. beacon_target invariants
# ===========================================================================

def test_beacon_target_security_invariants(db_session):
    """
    Ensures offline SMS beacons are strictly sent only to cryptographically
    confirmed phone numbers — never to unverified ones.
    """
    # 1. Null user → None
    assert beacon_target(None) is None

    # 2. User with no phone at all → None
    user_no_phone = User(
        id=generate_uuid(),
        username="beacon_no_phone",
        email="beacon_no_phone@example.com",
        hashed_password="pw",
        fallback_phone_e164=None,
        phone_verified_at=None
    )
    assert beacon_target(user_no_phone) is None

    # 3. Phone present but NOT verified → None (the critical invariant)
    user_unverified = User(
        id=generate_uuid(),
        username="beacon_unverified",
        email="beacon_unverified@example.com",
        hashed_password="pw",
        fallback_phone_e164=VALID_IN_PHONE,
        phone_verified_at=None
    )
    assert beacon_target(user_unverified) is None

    # 4. Phone present AND verified → returns the E.164 number
    now = utc_now()
    user_verified = User(
        id=generate_uuid(),
        username="beacon_verified",
        email="beacon_verified@example.com",
        hashed_password="pw",
        fallback_phone_e164=VALID_IN_PHONE,
        phone_verified_at=now
    )
    assert beacon_target(user_verified) == VALID_IN_PHONE

    # 5. dispatch_sms_beacon honours the same invariant
    assert dispatch_sms_beacon(user_unverified, "AT1:GPS:12.34,56.78") is False
    assert dispatch_sms_beacon(user_verified, "AT1:GPS:12.34,56.78") is True


# ===========================================================================
# 2. KYC validation on POST /verify/phone/start
# ===========================================================================

def test_phone_start_rejects_invalid_format(client, verified_owner_user):
    """Invalid format (no country code) is rejected with 422."""
    token, user = verified_owner_user
    resp = client.post(
        "/verify/phone/start",
        headers={"Authorization": f"Bearer {token}"},
        json={"phone_number": "invalid-phone"}
    )
    assert resp.status_code == 422
    assert "Invalid international phone number format" in resp.json()["detail"]


def test_phone_start_rejects_landline(client, verified_owner_user):
    """FIXED_LINE numbers are rejected with 422."""
    token, user = verified_owner_user
    # +91 11 XXXX XXXX is a Delhi landline
    resp = client.post(
        "/verify/phone/start",
        headers={"Authorization": f"Bearer {token}"},
        json={"phone_number": "+911123456789"}
    )
    assert resp.status_code == 422
    assert "landline" in resp.json()["detail"].lower() or "fixed-line" in resp.json()["detail"].lower()


def test_phone_start_requires_active_guardian_device(client, verified_owner_user, db_session):
    """No paired Guardian → 409 Conflict with no_active_guardian_device detail."""
    token, user = verified_owner_user

    # Make sure no device exists for this user
    db_session.query(ProtectedDevice).filter(ProtectedDevice.user_id == user.id).delete()
    db_session.commit()

    resp = client.post(
        "/verify/phone/start",
        headers={"Authorization": f"Bearer {token}"},
        json={"phone_number": VALID_IN_PHONE}
    )
    assert resp.status_code == 409
    assert "no_active_guardian_device" in resp.json()["detail"]


# ===========================================================================
# 3. Full phone verification E2E flow
# ===========================================================================

def _make_active_guardian(db_session, user_id: str) -> ProtectedDevice:
    """Helper: create a fully paired, ACTIVE Guardian device with pairing_secret stored."""
    raw_secret = "TestPairingSecret12345678901234"  # 30 chars, no sequential runs
    import hashlib
    secret_hash = hashlib.sha256(raw_secret.encode("utf-8")).hexdigest()
    device = ProtectedDevice(
        id=generate_uuid(),
        user_id=user_id,
        device_alias="Guardian Pixel 8",
        public_key_pem="-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest==\n-----END PUBLIC KEY-----",
        trusted_phone_number=VALID_IN_PHONE,
        pairing_secret_hash=secret_hash,
        pairing_secret=raw_secret,
        pairing_id=generate_uuid(),
        status="ACTIVE"
    )
    db_session.add(device)
    db_session.commit()
    db_session.refresh(device)
    return device


def test_phone_verification_e2e_flow(client, verified_owner_user, db_session):
    """
    Tests the full phone verification lifecycle:
      1. POST /verify/phone/start issues challenge, enqueues SEND_VERIFY_SMS
      2. Response never contains the raw OTP code
      3. SEND_VERIFY_SMS DeviceCommand has valid HMAC computed from stored pairing_secret
      4. Wrong code increments attempts counter
      5. Correct code sets phone_verified_at, updates Guardian trusted_phone_number, creates audit event
      6. beacon_target now returns the verified number
    """
    token, user = verified_owner_user
    guardian = _make_active_guardian(db_session, user.id)

    # --- Step 1: Start phone verification ---
    resp = client.post(
        "/verify/phone/start",
        headers={"Authorization": f"Bearer {token}"},
        json={"phone_number": VALID_IN_PHONE}
    )
    assert resp.status_code == 202, resp.json()
    data = resp.json()
    assert data["status"] == "challenge_issued"
    assert data["channel"] == "sms"
    assert data["target"] == VALID_IN_PHONE
    assert "code" not in data          # OTP must never appear in API response
    assert "command_id" in data

    # --- Step 2: Verify SEND_VERIFY_SMS command was enqueued ---
    cmd = db_session.query(DeviceCommand).filter(
        DeviceCommand.id == data["command_id"]
    ).first()
    assert cmd is not None
    assert cmd.command_type == "SEND_VERIFY_SMS"
    assert cmd.protected_device_id == guardian.id
    assert cmd.status == "PENDING"
    assert cmd.trusted_device_id is None  # Server-originated; no admin device

    params = json.loads(cmd.parameters_json)
    assert params["to"] == VALID_IN_PHONE
    assert "SHE verification code:" in params["body"]
    assert "code" not in str(cmd.admin_signature).lower()  # Signature is HMAC hex, not code

    # --- Step 3: Verify HMAC signature is correct ---
    canonical_bytes = create_canonical_command_bytes(
        pairing_id=guardian.pairing_id,
        command_id=cmd.id,
        target_device_id=guardian.id,
        command_type="SEND_VERIFY_SMS",
        sequence_counter=cmd.sequence_counter,
        expiry_epoch=cmd.expiry_epoch,
        parameters_json=cmd.parameters_json
    )
    expected_hmac = compute_truncated_hmac(
        guardian.pairing_secret.encode("utf-8"),
        canonical_bytes,
        hex_length=12
    )
    assert cmd.admin_signature.lower() == expected_hmac.lower()

    # --- Step 4: Extract OTP from command body (as Guardian reads from SMS) ---
    match = re.search(r'\b(\d{6})\b', params["body"])
    assert match is not None
    actual_code = match.group(1)

    # --- Step 5: Wrong code → 400, attempts incremented ---
    bad_resp = client.post(
        "/verify/phone/confirm",
        headers={"Authorization": f"Bearer {token}"},
        json={"code": "000000"}
    )
    assert bad_resp.status_code == 400
    assert "invalid_verification_code" in bad_resp.json()["detail"]

    ch = db_session.query(VerificationChallenge).filter(
        VerificationChallenge.user_id == user.id,
        VerificationChallenge.channel == "sms"
    ).first()
    assert ch is not None
    assert ch.attempts == 1

    # --- Step 6: Correct code → 200 OK ---
    good_resp = client.post(
        "/verify/phone/confirm",
        headers={"Authorization": f"Bearer {token}"},
        json={"code": actual_code}
    )
    assert good_resp.status_code == 200, good_resp.json()
    confirm_data = good_resp.json()
    assert confirm_data["status"] == "phone_verified"
    assert confirm_data["fallback_phone_e164"] == VALID_IN_PHONE
    assert confirm_data["phone_verified_at"] is not None

    # --- Step 7: User model updated ---
    db_session.refresh(user)
    assert user.fallback_phone_e164 == VALID_IN_PHONE
    assert user.phone_verified_at is not None

    # --- Step 8: beacon_target now returns the verified number ---
    assert beacon_target(user) == VALID_IN_PHONE

    # --- Step 9: Guardian trusted_phone_number synced ---
    db_session.refresh(guardian)
    assert guardian.trusted_phone_number == VALID_IN_PHONE

    # --- Step 10: AuditEvent logged ---
    audit = db_session.query(AuditEvent).filter(
        AuditEvent.user_id == user.id,
        AuditEvent.event_type == "PHONE_VERIFIED"
    ).first()
    assert audit is not None


def test_phone_change_clears_verification(client, verified_owner_user, db_session):
    """
    Changing the fallback phone number clears phone_verified_at so the new
    number must be re-verified before beacons can be sent to it.
    """
    token, user = verified_owner_user
    guardian = _make_active_guardian(db_session, user.id)

    # First: verify the initial number
    resp = client.post(
        "/verify/phone/start",
        headers={"Authorization": f"Bearer {token}"},
        json={"phone_number": VALID_IN_PHONE}
    )
    assert resp.status_code == 202
    cmd = db_session.query(DeviceCommand).filter(
        DeviceCommand.id == resp.json()["command_id"]
    ).first()
    params = json.loads(cmd.parameters_json)
    code = re.search(r'\b(\d{6})\b', params["body"]).group(1)
    client.post(
        "/verify/phone/confirm",
        headers={"Authorization": f"Bearer {token}"},
        json={"code": code}
    )
    db_session.refresh(user)
    assert user.phone_verified_at is not None

    # Now: start verification with a different number → should clear phone_verified_at
    resp2 = client.post(
        "/verify/phone/start",
        headers={"Authorization": f"Bearer {token}"},
        json={"phone_number": VALID_IN_PHONE_2}
    )
    assert resp2.status_code == 202
    db_session.refresh(user)
    # phone_verified_at cleared until new number is confirmed
    assert user.phone_verified_at is None
    assert beacon_target(user) is None  # Beacon blocked during change


def test_phone_start_requires_pairing_secret(client, verified_owner_user, db_session):
    """
    A device paired before migration 0005 (pairing_secret=None) must be
    re-paired before phone verification is available.
    """
    token, user = verified_owner_user

    # Legacy device: has pairing_secret_hash but NOT pairing_secret
    import hashlib
    legacy = ProtectedDevice(
        id=generate_uuid(),
        user_id=user.id,
        device_alias="Legacy Guardian",
        public_key_pem="-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest==\n-----END PUBLIC KEY-----",
        trusted_phone_number=VALID_IN_PHONE,
        pairing_secret_hash=hashlib.sha256(b"legacy").hexdigest(),
        pairing_secret=None,  # pre-migration device
        pairing_id=generate_uuid(),
        status="ACTIVE"
    )
    db_session.add(legacy)
    db_session.commit()

    resp = client.post(
        "/verify/phone/start",
        headers={"Authorization": f"Bearer {token}"},
        json={"phone_number": VALID_IN_PHONE}
    )
    assert resp.status_code == 409
    assert "no_pairing_secret" in resp.json()["detail"]


def test_phone_confirm_without_active_challenge_returns_400(client, verified_owner_user):
    """Calling /confirm before /start returns 400 with meaningful error."""
    token, user = verified_owner_user
    resp = client.post(
        "/verify/phone/confirm",
        headers={"Authorization": f"Bearer {token}"},
        json={"code": "123456"}
    )
    assert resp.status_code == 400
    assert "challenge_invalid_or_consumed" in resp.json()["detail"]
