import base64
import json
import time
import pytest
from datetime import datetime, timezone, timedelta
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization

from app.main import app
from app.core.config import settings
from conftest import enroll_totp_and_get_2fa_token


@pytest.fixture
def keypair():
    """Generates an ECDSA P-256 keypair for testing."""
    priv = ec.generate_private_key(ec.SECP256R1())
    pub_pem = priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    return priv, pub_pem


def get_current_totp_code(client, token_1fa):
    """Helper to retrieve current valid TOTP code for user."""
    from app.core.security import verify_access_token, decrypt_totp_secret, generate_totp_secret
    from app.db.models import User, utc_now
    from conftest import TestingSessionLocal
    import struct
    import hmac
    import hashlib

    db = TestingSessionLocal()
    try:
        payload = verify_access_token(token_1fa)
        user = db.query(User).filter(User.id == payload["sub"]).first()
        secret = decrypt_totp_secret(user.totp_secret)
        key = base64.b32decode(secret + "=" * ((8 - len(secret) % 8) % 8), casefold=True)
        counter = int(time.time() // 30)
        msg = struct.pack(">Q", counter)
        h = hmac.new(key, msg, hashlib.sha1).digest()
        offset = h[-1] & 0x0F
        truncated = struct.unpack(">I", h[offset:offset + 4])[0] & 0x7FFFFFFF
        code = truncated % 1000000
        return f"{code:06d}"
    finally:
        db.close()


def test_pairing_session_creation_requires_totp(client, monkeypatch):
    """Verifies that initiating a pairing session strictly mandates valid TOTP."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)

    client.post("/auth/register", json={
        "username": "pairing_user",
        "email": "pairing@example.com",
        "password": "Password123!"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "pairing_user",
        "password": "Password123!"
    })
    raw_token = login_resp.json()["access_token"]
    token_2fa = enroll_totp_and_get_2fa_token(client, raw_token)

    # 1. Attempt with invalid TOTP code -> 400
    bad_resp = client.post(
        "/api/v1/pairing-sessions",
        headers={"Authorization": f"Bearer {token_2fa}"},
        json={"totp_code": "000000"}
    )
    assert bad_resp.status_code == 400
    assert "Invalid TOTP" in bad_resp.json()["detail"]

    # 2. Attempt with valid TOTP code -> 201 Created
    valid_code = get_current_totp_code(client, token_2fa)
    good_resp = client.post(
        "/api/v1/pairing-sessions",
        headers={"Authorization": f"Bearer {token_2fa}"},
        json={"totp_code": valid_code}
    )
    assert good_resp.status_code == 201
    data = good_resp.json()
    assert "session_token" in data
    assert "short_code" in data
    assert len(data["short_code"]) == 6
    assert data["expires_in_seconds"] == 300


def test_guardian_pairing_with_session_token_and_identity(client, keypair, monkeypatch):
    """Verifies Guardian enrollment using pairing session token and reporting full hardware identity."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)
    priv_key, pub_pem = keypair

    client.post("/auth/register", json={
        "username": "guardian_owner",
        "email": "g_owner@example.com",
        "password": "Password123!"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "guardian_owner",
        "password": "Password123!"
    })
    token = enroll_totp_and_get_2fa_token(client, login_resp.json()["access_token"])
    totp_code = get_current_totp_code(client, token)

    # Create 5-minute pairing session
    sess_resp = client.post(
        "/api/v1/pairing-sessions",
        headers={"Authorization": f"Bearer {token}"},
        json={"totp_code": totp_code}
    )
    assert sess_resp.status_code == 201
    sess_token = sess_resp.json()["session_token"]

    # Enroll Guardian handset with full hardware identity & permission health
    device_id = "018f45a1-7c3d-711e-9d21-aabbccddeeff"
    pair_resp = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess_token}"},
        json={
            "device_id": device_id,
            "device_alias": "Sam's Pixel 8",
            "public_key_pem": pub_pem,
            "trusted_phone_number": "+15551234567",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899",
            "manufacturer": "Google",
            "brand": "google",
            "model": "Pixel 8",
            "android_version": "14",
            "sdk_level": 34,
            "variant": "hardened",
            "app_version": "1.0.0",
            "is_device_owner": True,
            "battery_level": 88,
            "connectivity_type": "wifi",
            "sim_operator": "T-Mobile",
            "sim_country": "US",
            "sim_changed": False,
            "permission_health": {
                "sms": True,
                "background_location": True,
                "camera": True,
                "battery_optimization_exempt": True,
                "restricted_settings_unlocked": True
            }
        }
    )
    assert pair_resp.status_code == 201
    dev_data = pair_resp.json()
    assert dev_data["id"] == device_id
    assert dev_data["device_alias"] == "Sam's Pixel 8"
    assert dev_data["model"] == "Pixel 8"
    assert dev_data["variant"] == "hardened"
    assert dev_data["is_device_owner"] is True
    assert dev_data["status_info"]["battery_level"] == 88
    assert dev_data["status_info"]["permission_health"]["background_location"] is True
    assert "confirmation_code" in dev_data

    # Verify pairing session is now consumed (single-use)
    reused_resp = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess_token}"},
        json={
            "device_id": "018f45a1-7c3d-711e-9d21-998877665544",
            "device_alias": "Second Device",
            "public_key_pem": pub_pem,
            "trusted_phone_number": "+15551234567",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899"
        }
    )
    assert reused_resp.status_code == 401
    assert "already been consumed" in reused_resp.json()["detail"]


def test_duplicate_device_id_and_key_fingerprint_rejected(client, keypair, monkeypatch):
    """Verifies that active duplicate device IDs and duplicate public keys are strictly rejected."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)
    priv_key, pub_pem = keypair

    client.post("/auth/register", json={
        "username": "dup_user",
        "email": "dup@example.com",
        "password": "Password123!"
    })
    token = enroll_totp_and_get_2fa_token(client, client.post("/auth/login/json", json={"username": "dup_user", "password": "Password123!"}).json()["access_token"])

    # First pairing
    sess1 = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
    pair1 = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess1}"},
        json={
            "device_id": "018f45a1-7c3d-711e-9d21-000000000001",
            "device_alias": "First Device",
            "public_key_pem": pub_pem,
            "trusted_phone_number": "+15551112222",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899"
        }
    )
    assert pair1.status_code == 201

    # Confirm first device to ACTIVE
    client.post(
        f"/api/v1/devices/018f45a1-7c3d-711e-9d21-000000000001/confirm-pairing",
        headers={"Authorization": f"Bearer {token}"},
        json={"confirmation_code": pair1.json()["confirmation_code"]}
    )

    # Attempt second device with same active device_id -> 409 Conflict
    sess2 = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
    dup_id_resp = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess2}"},
        json={
            "device_id": "018f45a1-7c3d-711e-9d21-000000000001",
            "device_alias": "Duplicate ID",
            "public_key_pem": pub_pem,
            "trusted_phone_number": "+15551112222",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899"
        }
    )
    assert dup_id_resp.status_code == 409
    assert "already paired and active" in dup_id_resp.json()["detail"]

    # Attempt second device with different ID but SAME public key -> 409 Conflict
    sess3 = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
    dup_key_resp = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess3}"},
        json={
            "device_id": "018f45a1-7c3d-711e-9d21-000000000002",
            "device_alias": "Duplicate Key",
            "public_key_pem": pub_pem,
            "trusted_phone_number": "+15551112222",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899"
        }
    )
    assert dup_key_resp.status_code == 409
    assert "cryptographic key pair is already registered" in dup_key_resp.json()["detail"]


def test_reinstall_detection_flags_previous_device(client, monkeypatch):
    """Verifies that pairing a new device ID with matching brand/model flags the prior device as possible reinstall."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)

    client.post("/auth/register", json={
        "username": "reinstall_user",
        "email": "reinstall@example.com",
        "password": "Password123!"
    })
    token = enroll_totp_and_get_2fa_token(client, client.post("/auth/login/json", json={"username": "reinstall_user", "password": "Password123!"}).json()["access_token"])

    # Device 1: Moto G32
    priv1 = ec.generate_private_key(ec.SECP256R1())
    pub1 = priv1.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    sess1 = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
    p1 = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess1}"},
        json={
            "device_id": "018f45a1-7c3d-711e-9d21-111111111111",
            "device_alias": "Moto G32 Old",
            "public_key_pem": pub1,
            "trusted_phone_number": "+15551112222",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899",
            "brand": "motorola",
            "model": "moto g32"
        }
    )
    assert p1.status_code == 201

    # Device 2: Fresh reinstall gets new ID & new key, but matching brand & model
    priv2 = ec.generate_private_key(ec.SECP256R1())
    pub2 = priv2.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    sess2 = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
    p2 = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess2}"},
        json={
            "device_id": "018f45a1-7c3d-711e-9d21-222222222222",
            "device_alias": "Moto G32 New",
            "public_key_pem": pub2,
            "trusted_phone_number": "+15551112222",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899",
            "brand": "motorola",
            "model": "moto g32"
        }
    )
    assert p2.status_code == 201

    # Check that Old Device is now flagged as possible reinstall
    old_dev = client.get("/api/v1/devices/018f45a1-7c3d-711e-9d21-111111111111", headers={"Authorization": f"Bearer {token}"}).json()
    assert old_dev["is_possible_reinstall"] is True


def test_max_guardian_account_limit_enforced(client, monkeypatch):
    """Verifies that exceeding the 5 Guardian limit per account is blocked."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)
    monkeypatch.setattr(settings, "MAX_GUARDIANS_PER_ACCOUNT", 2)  # Set threshold to 2 for fast test

    client.post("/auth/register", json={
        "username": "limit_user",
        "email": "limit@example.com",
        "password": "Password123!"
    })
    token = enroll_totp_and_get_2fa_token(client, client.post("/auth/login/json", json={"username": "limit_user", "password": "Password123!"}).json()["access_token"])

    for i in range(1, 3):
        priv = ec.generate_private_key(ec.SECP256R1())
        pub = priv.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        sess = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
        resp = client.post(
            "/api/v1/devices/pair",
            headers={"Authorization": f"Bearer {sess}"},
            json={
                "device_id": f"018f45a1-7c3d-711e-9d21-00000000000{i}",
                "device_alias": f"Device {i}",
                "public_key_pem": pub,
                "trusted_phone_number": "+15551112222",
                "pairing_secret": "AABBCCDDEEFF00112233445566778899"
            }
        )
        assert resp.status_code == 201

    # 3rd handset must be blocked
    priv3 = ec.generate_private_key(ec.SECP256R1())
    pub3 = priv3.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    sess3 = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
    blocked = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess3}"},
        json={
            "device_id": "018f45a1-7c3d-711e-9d21-000000000003",
            "device_alias": "Device 3",
            "public_key_pem": pub3,
            "trusted_phone_number": "+15551112222",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899"
        }
    )
    assert blocked.status_code == 400
    assert "limit of 2 protected devices" in blocked.json()["detail"]


def test_device_rename_and_audit_history(client, keypair, monkeypatch):
    """Verifies renaming a handset and retrieving tamper-evident audit history."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)
    priv_key, pub_pem = keypair

    client.post("/auth/register", json={
        "username": "audit_user",
        "email": "audit@example.com",
        "password": "Password123!"
    })
    token = enroll_totp_and_get_2fa_token(client, client.post("/auth/login/json", json={"username": "audit_user", "password": "Password123!"}).json()["access_token"])

    sess = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
    dev_id = "018f45a1-7c3d-711e-9d21-333333333333"
    pair_resp = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess}"},
        json={
            "device_id": dev_id,
            "device_alias": "Original Name",
            "public_key_pem": pub_pem,
            "trusted_phone_number": "+15551112222",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899"
        }
    )
    assert pair_resp.status_code == 201

    # Rename
    patch_resp = client.patch(
        f"/api/v1/devices/{dev_id}",
        headers={"Authorization": f"Bearer {token}"},
        json={"nickname": "Renamed Guardian"}
    )
    assert patch_resp.status_code == 200
    assert patch_resp.json()["device_alias"] == "Renamed Guardian"

    # Fetch audit log
    audit_resp = client.get(f"/api/v1/devices/{dev_id}/audit-log", headers={"Authorization": f"Bearer {token}"})
    assert audit_resp.status_code == 200
    actions = [a["action"] for a in audit_resp.json()]
    assert "PAIR" in actions
    assert "RENAME" in actions


def test_signed_guardian_heartbeat(client, keypair, monkeypatch):
    """Verifies that Guardian periodic heartbeat validates cryptographic signature and anti-replay window."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)
    priv_key, pub_pem = keypair

    client.post("/auth/register", json={
        "username": "hb_user",
        "email": "hb@example.com",
        "password": "Password123!"
    })
    token = enroll_totp_and_get_2fa_token(client, client.post("/auth/login/json", json={"username": "hb_user", "password": "Password123!"}).json()["access_token"])
    sess = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
    dev_id = "018f45a1-7c3d-711e-9d21-444444444444"
    client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess}"},
        json={
            "device_id": dev_id,
            "device_alias": "Heartbeat Phone",
            "public_key_pem": pub_pem,
            "trusted_phone_number": "+15551112222",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899"
        }
    )

    # 1. Valid signed heartbeat
    now_ts = int(time.time())
    canonical_data = f"{dev_id}:{now_ts}:92:cellular:False".encode("utf-8")
    signature = base64.b64encode(priv_key.sign(canonical_data, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    hb_resp = client.post(
        f"/api/v1/devices/{dev_id}/heartbeat",
        json={
            "battery_level": 92,
            "connectivity_type": "cellular",
            "sim_operator": "Airtel",
            "sim_country": "IN",
            "sim_changed": False,
            "permission_health": {"sms": True, "background_location": True},
            "capabilities": ["locate_sms", "live_gps", "siren", "ring", "battery_report"],
            "timestamp": now_ts,
            "signature": signature
        }
    )
    assert hb_resp.status_code == 200
    assert hb_resp.json()["status"] == "HEARTBEAT_ACK"

    # Verify DeviceStatus updated
    dev_detail = client.get(f"/api/v1/devices/{dev_id}", headers={"Authorization": f"Bearer {token}"}).json()
    assert dev_detail["status_info"]["battery_level"] == 92
    assert dev_detail["status_info"]["sim_operator"] == "Airtel"
    assert dev_detail["status_info"]["capabilities"] == ["locate_sms", "live_gps", "siren", "ring", "battery_report"]
    assert dev_detail["status_info"]["is_stale"] is False

    # 2. Replay / expired timestamp rejection (older than 300s)
    stale_ts = now_ts - 500
    stale_data = f"{dev_id}:{stale_ts}:92:cellular:False".encode("utf-8")
    stale_sig = base64.b64encode(priv_key.sign(stale_data, ec.ECDSA(hashes.SHA256()))).decode("utf-8")
    stale_resp = client.post(
        f"/api/v1/devices/{dev_id}/heartbeat",
        json={
            "battery_level": 92,
            "connectivity_type": "cellular",
            "sim_changed": False,
            "timestamp": stale_ts,
            "signature": stale_sig
        }
    )
    assert stale_resp.status_code == 400
    assert "clock skew" in stale_resp.json()["detail"]

    # 3. Invalid forged signature rejection
    forged_resp = client.post(
        f"/api/v1/devices/{dev_id}/heartbeat",
        json={
            "battery_level": 92,
            "connectivity_type": "cellular",
            "sim_changed": False,
            "timestamp": now_ts,
            "signature": base64.b64encode(b"forged_invalid_signature_bytes").decode()
        }
    )
    assert forged_resp.status_code == 401
    assert "Invalid cryptographic signature" in forged_resp.json()["detail"]


def test_graceful_unpair_and_force_revoke(client, keypair, monkeypatch):
    """Verifies graceful unpair queuing and force-revoke termination with explicit warnings."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)
    priv_key, pub_pem = keypair

    client.post("/auth/register", json={
        "username": "unpair_user",
        "email": "unpair@example.com",
        "password": "Password123!"
    })
    token = enroll_totp_and_get_2fa_token(client, client.post("/auth/login/json", json={"username": "unpair_user", "password": "Password123!"}).json()["access_token"])
    sess = client.post("/api/v1/pairing-sessions", headers={"Authorization": f"Bearer {token}"}, json={"totp_code": get_current_totp_code(client, token)}).json()["session_token"]
    dev_id = "018f45a1-7c3d-711e-9d21-555555555555"
    client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {sess}"},
        json={
            "device_id": dev_id,
            "device_alias": "Handset to Remove",
            "public_key_pem": pub_pem,
            "trusted_phone_number": "+15551112222",
            "pairing_secret": "AABBCCDDEEFF00112233445566778899"
        }
    )

    # 1. Graceful unpair: Requires valid TOTP
    totp = get_current_totp_code(client, token)
    unpair_resp = client.post(
        f"/api/v1/devices/{dev_id}/unpair",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "totp_code": totp,
            "admin_signature": "MEQCIAz7x...",
            "sequence_counter": 1,
            "expiry_epoch": int(time.time()) + 3600
        }
    )
    assert unpair_resp.status_code == 200
    assert unpair_resp.json()["status"] == "QUEUED"
    assert unpair_resp.json()["command_type"] == "UNPAIR"

    # 2. Force Revoke (lost/stolen handset)
    totp2 = get_current_totp_code(client, token)
    revoke_resp = client.delete(
        f"/api/v1/devices/{dev_id}?action=revoke",
        headers={"Authorization": f"Bearer {token}", "x-totp-code": totp2}
    )
    assert revoke_resp.status_code == 200
    assert revoke_resp.json()["status"] == "REVOKED"
    assert "terminated" in revoke_resp.json()["warning"]

    # Verify device no longer appears in active roster
    roster = client.get("/api/v1/devices", headers={"Authorization": f"Bearer {token}"}).json()
    assert dev_id not in [d["id"] for d in roster["devices"]]
