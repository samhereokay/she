import os
import json
import pytest
import base64
from datetime import datetime, timezone, timedelta
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization

from app.main import app
from app.db.session import Base, get_db
from app.api.locations import format_canonical_location_string
from app.core.security import compute_truncated_hmac, verify_truncated_hmac, verify_ecdsa_p256_signature
from app.core.rate_limit import SimpleRateLimiter
from app.core.config import settings

client = TestClient(app)


@pytest.fixture
def test_keypair():
    """Generate ECDSA P-256 test keypair."""
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_key = private_key.public_key()
    
    pem_public = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    
    return private_key, pem_public


def test_health_endpoint():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "HEALTHY"


def test_auth_and_user_registration():
    # 1. Register User
    reg_resp = client.post("/auth/register", json={
        "username": "owner_alice",
        "email": "alice@example.com",
        "password": "SuperSecurePassword123!"
    })
    assert reg_resp.status_code == 201
    user_data = reg_resp.json()
    assert user_data["username"] == "owner_alice"
    assert "hashed_password" not in user_data

    # 2. Login via JSON
    login_resp = client.post("/auth/login/json", json={
        "username": "owner_alice",
        "password": "SuperSecurePassword123!"
    })
    assert login_resp.status_code == 200
    token_data = login_resp.json()
    assert "access_token" in token_data
    assert token_data["token_type"] == "bearer"

    # 3. Read current user with token
    headers = {"Authorization": f"Bearer {token_data['access_token']}"}
    me_resp = client.get("/auth/me", headers=headers)
    assert me_resp.status_code == 200
    assert me_resp.json()["email"] == "alice@example.com"


def test_device_pairing_and_list(test_keypair):
    _, pem_public = test_keypair
    
    # Register & Login
    client.post("/auth/register", json={
        "username": "owner_bob",
        "email": "bob@example.com",
        "password": "PasswordBob123!"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "owner_bob",
        "password": "PasswordBob123!"
    })
    token = login_resp.json()["access_token"]
    from conftest import enroll_totp_and_get_2fa_token
    token = enroll_totp_and_get_2fa_token(client, token)
    headers = {"Authorization": f"Bearer {token}"}

    # 1. Request single-use pairing token
    pt_resp = client.post("/auth/pairing-token", headers=headers, json={})
    assert pt_resp.status_code == 200
    pt = pt_resp.json()["pairing_token"]

    # 2. Pair Guardian phone using single-use pairing token
    pair_resp = client.post("/api/v1/devices/pair", headers={"Authorization": f"Bearer {pt}"}, json={
        "device_alias": "Bob Pixel 8",
        "public_key_pem": pem_public,
        "trusted_phone_number": "+15551234567",
        "pairing_secret": "48f12c8b90a4e76d1234567890abcdef"
    })
    assert pair_resp.status_code == 201
    device_data = pair_resp.json()
    assert device_data["device_alias"] == "Bob Pixel 8"
    assert device_data["status"] == "PENDING_CONFIRMATION"
    assert device_data["confirmation_code"] is not None

    # 3. Confirm pairing with matching confirmation code
    conf_resp = client.post(
        f"/api/v1/devices/{device_data['id']}/confirm-pairing",
        headers=headers,
        json={"confirmation_code": device_data["confirmation_code"]}
    )
    assert conf_resp.status_code == 200
    assert conf_resp.json()["status"] == "ACTIVE"
    assert conf_resp.json()["pairing_id"] is not None

    # List devices
    list_resp = client.get("/api/v1/devices", headers=headers)
    assert list_resp.status_code == 200
    devices = list_resp.json()["devices"]
    assert len(devices) == 1
    assert devices[0]["id"] == device_data["id"]


def test_location_batch_ingest_and_signature_verification(client, test_keypair, pair_device):
    private_key, pem_public = test_keypair
    
    # 1. Setup Owner & Device
    client.post("/auth/register", json={
        "username": "owner_carol",
        "email": "carol@example.com",
        "password": "PasswordCarol123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "owner_carol",
        "password": "PasswordCarol123!"
    }).json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    device_id, _ = pair_device(
        client,
        token,
        "Carol Phone",
        pem_public,
        "+15559876543",
        "secretpairingkey1234567890abcdef"
    )

    # 2. Construct signed location event
    now = datetime.now(timezone.utc)
    capture_epoch = int(now.timestamp())
    lat, lng, acc = 37.774929, -122.419416, 12.5
    seq = 1

    canonical_bytes = format_canonical_location_string(
        device_id=device_id,
        sequence_number=seq,
        capture_timestamp_sec=capture_epoch,
        latitude=lat,
        longitude=lng,
        accuracy=acc
    )
    
    # Sign with private key
    der_signature = private_key.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))
    sig_b64 = base64.b64encode(der_signature).decode("utf-8")

    # 3. Post batch ingest (valid signature)
    batch_payload = {
        "device_id": device_id,
        "events": [
            {
                "sequence_number": seq,
                "latitude": lat,
                "longitude": lng,
                "accuracy": acc,
                "altitude": 45.0,
                "velocity": 1.2,
                "capture_time": now.isoformat(),
                "signature": sig_b64
            }
        ]
    }
    
    ingest_resp = client.post("/api/v1/locations/batch", json=batch_payload)
    assert ingest_resp.status_code == 200
    res = ingest_resp.json()
    assert res["status"] == "PROCESSED"
    assert res["acknowledged_sequences"] == [1]
    assert res["rejected_sequences"] == []

    # 4. Check device status is now RECENT (based on server_receipt_time)
    dev_resp = client.get(f"/api/v1/devices/{device_id}", headers=headers)
    assert dev_resp.status_code == 200
    assert dev_resp.json()["calculated_status"] == "RECENT"
    assert dev_resp.json()["last_location"]["sequence_number"] == 1

    # 5. Test Forged Signature Rejection
    bad_payload = {
        "device_id": device_id,
        "events": [
            {
                "sequence_number": 2,
                "latitude": lat + 0.01,
                "longitude": lng + 0.01,
                "accuracy": acc,
                "capture_time": now.isoformat(),
                "signature": base64.b64encode(b"forged_invalid_der_bytes").decode("utf-8")
            }
        ]
    }
    bad_resp = client.post("/api/v1/locations/batch", json=bad_payload)
    assert bad_resp.status_code == 200
    bad_res = bad_resp.json()
    assert bad_res["rejected_sequences"] == [2]
    assert bad_res["acknowledged_sequences"] == []


def test_idempotent_offline_queue_reconnect(client, test_keypair, pair_device):
    """Simulates Guardian phone collecting 5 events offline, then batch syncing on reconnect."""
    private_key, pem_public = test_keypair
    
    # Register & Pair
    client.post("/auth/register", json={
        "username": "owner_dave",
        "email": "dave@example.com",
        "password": "PasswordDave123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "owner_dave",
        "password": "PasswordDave123!"
    }).json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    device_id, _ = pair_device(
        client,
        token,
        "Dave Offline Phone",
        pem_public,
        "+15553334444",
        "davesecret1234567890abcdef123456"
    )

    # Generate 5 offline events
    events = []
    base_time = datetime.now(timezone.utc) - timedelta(minutes=10)
    for seq in range(1, 6):
        cap_time = base_time + timedelta(minutes=seq)
        epoch = int(cap_time.timestamp())
        lat = 40.7128 + (seq * 0.001)
        lng = -74.0060 + (seq * 0.001)
        acc = 10.0
        
        canonical_bytes = format_canonical_location_string(
            device_id=device_id,
            sequence_number=seq,
            capture_timestamp_sec=epoch,
            latitude=lat,
            longitude=lng,
            accuracy=acc
        )
        sig = private_key.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))
        events.append({
            "sequence_number": seq,
            "latitude": lat,
            "longitude": lng,
            "accuracy": acc,
            "capture_time": cap_time.isoformat(),
            "signature": base64.b64encode(sig).decode("utf-8")
        })

    # Sync all 5 events at once
    batch_payload = {"device_id": device_id, "events": events}
    resp = client.post("/api/v1/locations/batch", json=batch_payload)
    assert resp.status_code == 200
    assert resp.json()["acknowledged_sequences"] == [1, 2, 3, 4, 5]

    # Replay identical batch (idempotency test)
    replay_resp = client.post("/api/v1/locations/batch", json=batch_payload)
    assert replay_resp.status_code == 200
    assert replay_resp.json()["acknowledged_sequences"] == [1, 2, 3, 4, 5]

    # Check history contains all 5
    hist_resp = client.get(f"/api/v1/devices/{device_id}/locations", headers=headers)
    assert hist_resp.status_code == 200
    assert len(hist_resp.json()) == 5


def test_reject_same_sequence_different_payload(client, test_keypair, pair_device):
    """Verifies that replaying a sequence number with altered coordinates is rejected."""
    private_key, pem_public = test_keypair

    client.post("/auth/register", json={
        "username": "owner_eve",
        "email": "eve@example.com",
        "password": "PasswordEve123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "owner_eve",
        "password": "PasswordEve123!"
    }).json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    device_id, _ = pair_device(
        client,
        token,
        "Eve Phone",
        pem_public,
        "+15550001111",
        "evesecret1234567890abcdef123456"
    )

    now = datetime.now(timezone.utc)
    epoch = int(now.timestamp())
    seq = 1

    # Ingest original event
    orig_bytes = format_canonical_location_string(device_id, seq, epoch, 30.0, -90.0, 5.0)
    orig_sig = base64.b64encode(private_key.sign(orig_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    resp1 = client.post("/api/v1/locations/batch", json={
        "device_id": device_id,
        "events": [{
            "sequence_number": seq,
            "latitude": 30.0,
            "longitude": -90.0,
            "accuracy": 5.0,
            "capture_time": now.isoformat(),
            "signature": orig_sig
        }]
    })
    assert resp1.status_code == 200
    assert resp1.json()["acknowledged_sequences"] == [1]

    # Attempt to replay seq=1 with completely different coordinates (e.g. spoofed replay)
    altered_bytes = format_canonical_location_string(device_id, seq, epoch, 40.0, -80.0, 5.0)
    altered_sig = base64.b64encode(private_key.sign(altered_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    resp2 = client.post("/api/v1/locations/batch", json={
        "device_id": device_id,
        "events": [{
            "sequence_number": seq,
            "latitude": 40.0,
            "longitude": -80.0,
            "accuracy": 5.0,
            "capture_time": now.isoformat(),
            "signature": altered_sig
        }]
    })
    assert resp2.status_code == 200
    # Must be rejected because sequence 1 already exists with different content!
    assert resp2.json()["rejected_sequences"] == [1]
    assert resp2.json()["acknowledged_sequences"] == []


def test_out_of_order_event_handling(client, test_keypair, pair_device):
    """Verifies that events delivered out-of-order over network reconnects are accepted and sorted."""
    private_key, pem_public = test_keypair

    client.post("/auth/register", json={
        "username": "owner_frank",
        "email": "frank@example.com",
        "password": "PasswordFrank123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "owner_frank",
        "password": "PasswordFrank123!"
    }).json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    device_id, _ = pair_device(
        client,
        token,
        "Frank Phone",
        pem_public,
        "+15552223333",
        "franksecret1234567890abcdef1234"
    )

    now = datetime.now(timezone.utc)
    
    # Ingest sequence 10 first
    b10 = format_canonical_location_string(device_id, 10, int(now.timestamp()), 50.0, 10.0, 5.0)
    s10 = base64.b64encode(private_key.sign(b10, ec.ECDSA(hashes.SHA256()))).decode("utf-8")
    r1 = client.post("/api/v1/locations/batch", json={
        "device_id": device_id,
        "events": [{
            "sequence_number": 10,
            "latitude": 50.0,
            "longitude": 10.0,
            "accuracy": 5.0,
            "capture_time": now.isoformat(),
            "signature": s10
        }]
    })
    assert r1.status_code == 200
    assert r1.json()["acknowledged_sequences"] == [10]

    # Ingest sequence 8 later
    b8 = format_canonical_location_string(device_id, 8, int(now.timestamp()) - 60, 49.9, 9.9, 5.0)
    s8 = base64.b64encode(private_key.sign(b8, ec.ECDSA(hashes.SHA256()))).decode("utf-8")
    r2 = client.post("/api/v1/locations/batch", json={
        "device_id": device_id,
        "events": [{
            "sequence_number": 8,
            "latitude": 49.9,
            "longitude": 9.9,
            "accuracy": 5.0,
            "capture_time": (now - timedelta(seconds=60)).isoformat(),
            "signature": s8
        }]
    })
    assert r2.status_code == 200
    assert r2.json()["acknowledged_sequences"] == [8]

    # Query history: ordered by sequence number descending
    hist = client.get(f"/api/v1/devices/{device_id}/locations", headers=headers).json()
    assert len(hist) == 2
    assert hist[0]["sequence_number"] == 10
    assert hist[1]["sequence_number"] == 8


def test_cross_user_authorization(client, test_keypair, pair_device):
    """Enforces multi-tenant authorization: User A cannot access User B's device or location history."""
    _, pem_public = test_keypair

    # 1. Register User A and User B (allowed when ALLOW_PUBLIC_REGISTRATION=True)
    settings.ALLOW_PUBLIC_REGISTRATION = True
    client.post("/auth/register", json={"username": "user_a", "email": "a@test.com", "password": "PasswordA123!"})
    client.post("/auth/register", json={"username": "user_b", "email": "b@test.com", "password": "PasswordB123!"})

    token_a = client.post("/auth/login/json", json={"username": "user_a", "password": "PasswordA123!"}).json()["access_token"]
    token_b = client.post("/auth/login/json", json={"username": "user_b", "password": "PasswordB123!"}).json()["access_token"]

    from app.db.models import User, utc_now
    from conftest import TestingSessionLocal
    _db = TestingSessionLocal()
    for _u in ["user_a", "user_b"]:
        _rec = _db.query(User).filter(User.username == _u).first()
        if _rec:
            _rec.email_verified_at = utc_now()
    _db.commit()
    _db.close()

    headers_a = {"Authorization": f"Bearer {token_a}"}
    headers_b = {"Authorization": f"Bearer {token_b}"}

    # 2. User A creates a protected device
    device_a_id, _ = pair_device(
        client,
        token_a,
        "Device A",
        pem_public,
        "+1111111111",
        "pairingsecretuserA1234567890abc"
    )

    # 3. User B attempts to read User A's device -> 404 (does not leak existence)
    resp_get = client.get(f"/api/v1/devices/{device_a_id}", headers=headers_b)
    assert resp_get.status_code == 404

    # 4. User B attempts to read User A's locations -> 404
    resp_loc = client.get(f"/api/v1/devices/{device_a_id}/locations", headers=headers_b)
    assert resp_loc.status_code == 404

    # 5. User B lists devices -> User A's device is NOT returned
    list_b = client.get("/api/v1/devices", headers=headers_b).json()["devices"]
    assert len(list_b) == 0


def test_shared_crypto_test_vectors():
    """Validates fixed cross-platform test vectors for ECDSA and 48-bit HMAC."""
    vector_path = os.path.join(os.path.dirname(__file__), "shared_crypto_test_vectors.json")
    with open(vector_path, "r", encoding="utf-8") as f:
        vectors = json.load(f)

    # 1. Test 48-bit HMAC Vector
    hmac_vec = vectors["hmac_48bit_vector"]
    key_bytes = hmac_vec["key_ascii"].encode("utf-8")
    msg_bytes = hmac_vec["message_ascii"].encode("utf-8")
    computed_hmac = compute_truncated_hmac(key_bytes, msg_bytes, hex_length=12)
    assert computed_hmac == hmac_vec["expected_truncated_hmac_48bit_hex"]
    assert verify_truncated_hmac(key_bytes, msg_bytes, hmac_vec["expected_truncated_hmac_48bit_hex"]) is True

    # 2. Test Inbound Command Vector
    cmd_vec = vectors["inbound_sms_command_vector"]
    cmd_key_bytes = cmd_vec["key_ascii"].encode("utf-8")
    cmd_msg_bytes = cmd_vec["command_body_without_hmac"].encode("utf-8")
    computed_cmd_hmac = compute_truncated_hmac(cmd_key_bytes, cmd_msg_bytes, hex_length=12)
    assert computed_cmd_hmac == cmd_vec["expected_truncated_hmac_48bit_hex"]

    # 3. Test ECDSA P-256 Vector
    ecdsa_vec = vectors["ecdsa_p256_vector"]
    data_bytes = ecdsa_vec["canonical_payload_string"].encode("utf-8")
    der_sig_bytes = base64.b64decode(ecdsa_vec["signature_der_base64"])
    
    # Must verify against public key
    assert verify_ecdsa_p256_signature(
        public_key_pem=ecdsa_vec["public_key_spki_pem"],
        data_bytes=data_bytes,
        der_signature_bytes=der_sig_bytes
    ) is True


def test_rate_limiter_logic():
    """Verifies that rate limiter rejects excessive requests exceeding threshold."""
    limiter = SimpleRateLimiter(requests_per_minute=3)
    
    class FakeRequest:
        def __init__(self, host):
            self.client = type("Client", (), {"host": host})()

    req = FakeRequest("192.168.1.100")
    
    # Requests 1, 2, 3 succeed
    limiter.check_rate_limit(req)
    limiter.check_rate_limit(req)
    limiter.check_rate_limit(req)
    
    # Request 4 must fail with 429
    with pytest.raises(Exception) as excinfo:
        limiter.check_rate_limit(req)
    assert "429" in str(excinfo.value)


def test_single_device_per_user_restriction():
    """Verify that a user account is strictly restricted to 1 protected device."""
    client = TestClient(app)
    # Register a new user
    u_resp = client.post("/auth/register", json={
        "username": "singledev_user",
        "email": "singledev@example.com",
        "password": "Password123!"
    })
    assert u_resp.status_code == 201

    login_resp = client.post("/auth/login/json", json={
        "username": "singledev_user",
        "password": "Password123!"
    })
    token = login_resp.json()["access_token"]
    from conftest import enroll_totp_and_get_2fa_token
    token = enroll_totp_and_get_2fa_token(client, token)

    # Pair Device 1
    pt_resp = client.post("/auth/pairing-token", headers={"Authorization": f"Bearer {token}"}, json={})
    assert pt_resp.status_code == 200
    pt1 = pt_resp.json()["pairing_token"]
    pair1 = client.post("/api/v1/devices/pair", headers={"Authorization": f"Bearer {pt1}"}, json={
        "device_alias": "Phone 1",
        "public_key_pem": "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest1\n-----END PUBLIC KEY-----",
        "trusted_phone_number": "+15551111111",
        "pairing_secret": "secret123456789012345678901234"
    })
    assert pair1.status_code == 201

    # Attempt to pair Device 2 on the same account -> MUST BE REJECTED with 400
    pt_resp2 = client.post("/auth/pairing-token", headers={"Authorization": f"Bearer {token}"}, json={})
    assert pt_resp2.status_code == 200
    pt2 = pt_resp2.json()["pairing_token"]
    pair2 = client.post("/api/v1/devices/pair", headers={"Authorization": f"Bearer {pt2}"}, json={
        "device_alias": "Phone 2",
        "public_key_pem": "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest2\n-----END PUBLIC KEY-----",
        "trusted_phone_number": "+15552222222",
        "pairing_secret": "secret123456789012345678901234"
    })
    assert pair2.status_code == 400
    assert "strictly restricted to 1 protected device" in pair2.json()["detail"]


def test_registration_token_verification(monkeypatch):
    """Verify that when REGISTRATION_TOKEN is configured, unverified signups are rejected."""
    from app.core.config import settings
    monkeypatch.setattr(settings, "REGISTRATION_TOKEN", "SECRET_INVITE_TOKEN_XYZ")

    client = TestClient(app)

    # Attempt registration without token -> REJECTED 403
    rej = client.post("/auth/register", json={
        "username": "unverified_bot",
        "email": "bot@example.com",
        "password": "Password123!"
    })
    assert rej.status_code == 403
    assert "verified invitation token" in rej.json()["detail"]

    # Registration with valid token -> SUCCESS 201
    ok = client.post("/auth/register", json={
        "username": "verified_user",
        "email": "verified@example.com",
        "password": "Password123!",
        "registration_token": "SECRET_INVITE_TOKEN_XYZ"
    })
    assert ok.status_code == 201
    assert ok.json()["username"] == "verified_user"


def test_automated_kyc_approval_without_token(monkeypatch):
    """Verify that authentic SHE APK clients with verified KYC are automatically approved without a token."""
    from app.core.config import settings
    monkeypatch.setattr(settings, "REGISTRATION_TOKEN", "SECRET_INVITE_TOKEN_XYZ")
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)

    client = TestClient(app)

    # 1. SHE Android app with genuine KYC profile -> AUTOMATIC APPROVAL (201)
    app_kyc_resp = client.post(
        "/auth/register",
        headers={"X-App-Client": "SHE-Admin-Android"},
        json={
            "username": "kyc_approved_owner",
            "email": "kyc_owner@example.com",
            "phone_number": "+919845123489",
            "device_info": "Motorola moto g32 (Android 13, API 33)",
            "password": "MasterSecurePassword123!"
        }
    )
    assert app_kyc_resp.status_code == 201
    data = app_kyc_resp.json()
    assert data["username"] == "kyc_approved_owner"
    assert data["phone_number"] == "+919845123489"
    assert "id" in data and len(data["id"]) > 10

    # 2. SHE Android app with fake/dummy device info -> REJECTED 403
    fake_dev_resp = client.post(
        "/auth/register",
        headers={"X-App-Client": "SHE-Admin-Android"},
        json={
            "username": "fake_dev_user",
            "email": "fake@example.com",
            "phone_number": "+919845123489",
            "device_info": "fake",
            "password": "MasterSecurePassword123!"
        }
    )
    assert fake_dev_resp.status_code == 403

    # 3. Invalid phone format -> REJECTED 422
    bad_phone_resp = client.post(
        "/auth/register",
        headers={"X-App-Client": "SHE-Admin-Android"},
        json={
            "username": "bad_phone_user",
            "email": "badphone@example.com",
            "phone_number": "not-a-phone",
            "device_info": "Motorola moto g32 (Android 13)",
            "password": "MasterSecurePassword123!"
        }
    )
    assert bad_phone_resp.status_code == 422

    # 4. Disposable email domain (e.g. mailinator.com) -> REJECTED 422
    disposable_resp = client.post(
        "/auth/register",
        headers={"X-App-Client": "SHE-Admin-Android"},
        json={
            "username": "disposable_user",
            "email": "scammer@mailinator.com",
            "phone_number": "+919845123489",
            "device_info": "Samsung SM-S918B (Android 14)",
            "password": "MasterSecurePassword123!"
        }
    )
    assert disposable_resp.status_code == 422
    assert "Disposable or temporary" in disposable_resp.json()["detail"]

    # 5. Email typo detection (e.g. gmial.com) -> REJECTED 422
    typo_resp = client.post(
        "/auth/register",
        headers={"X-App-Client": "SHE-Admin-Android"},
        json={
            "username": "typo_user",
            "email": "typo@gmial.com",
            "phone_number": "+919845123489",
            "device_info": "Samsung SM-S918B (Android 14)",
            "password": "MasterSecurePassword123!"
        }
    )
    assert typo_resp.status_code == 422
    assert "Did you mean @gmail.com" in typo_resp.json()["detail"]

    # 6. Sequential dummy phone number -> REJECTED 422
    dummy_phone_resp = client.post(
        "/auth/register",
        headers={"X-App-Client": "SHE-Admin-Android"},
        json={
            "username": "dummy_phone_user",
            "email": "dummy@example.com",
            "phone_number": "+919876543210",
            "device_info": "Samsung SM-S918B (Android 14)",
            "password": "MasterSecurePassword123!"
        }
    )
    assert dummy_phone_resp.status_code == 422
    assert "dummy placeholder" in dummy_phone_resp.json()["detail"]

    # 7. Indian landline prefix (not 6/7/8/9) -> REJECTED 422
    landline_resp = client.post(
        "/auth/register",
        headers={"X-App-Client": "SHE-Admin-Android"},
        json={
            "username": "landline_user",
            "email": "landline@example.com",
            "phone_number": "+911123456789",
            "device_info": "Samsung SM-S918B (Android 14)",
            "password": "MasterSecurePassword123!"
        }
    )
    assert landline_resp.status_code == 422
    assert "Fixed-line landline" in landline_resp.json()["detail"] or "start with 6, 7, 8, or 9" in landline_resp.json()["detail"]

    # 8. Android emulator registration in debug environment -> ACCEPTED (201)
    emulator_resp = client.post(
        "/auth/register",
        headers={"X-App-Client": "SHE-Admin-Android"},
        json={
            "username": "emulator_owner",
            "email": "emulator@example.com",
            "phone_number": "+919845123489",
            "device_info": "Google sdk_gphone64_arm64 (Android 14, API 34)",
            "password": "MasterSecurePassword123!"
        }
    )
    assert emulator_resp.status_code == 201
    assert emulator_resp.json()["username"] == "emulator_owner"


def test_kyc_validator_direct_unit_tests():
    """Direct unit tests for hardened kyc_validator.py functions."""
    from app.core.kyc_validator import (
        validate_email_kyc,
        validate_phone_kyc,
        validate_hardware_kyc,
        normalize_phone_number
    )

    # 1. Disposable email with subdomains loaded from file
    ok, err = validate_email_kyc("user@sub.mailinator.com")
    assert not ok
    assert "Disposable or temporary" in err

    ok, err = validate_email_kyc("user@dispostable.com")
    assert not ok
    assert "Disposable or temporary" in err

    # 2. Valid email passes
    ok, err = validate_email_kyc("user@example.com")
    assert ok and err is None

    # 3. Phonenumbers fixed-line landline detection
    ok, err = validate_phone_kyc("+911123456789")  # Delhi fixed line (011)
    assert not ok
    assert "Fixed-line landline" in err

    # 4. Valid mobile and normalization
    ok, err = validate_phone_kyc("+91 98451 23489")
    assert ok and err is None
    normalized = normalize_phone_number("+91 98451 23489")
    assert normalized == "+919845123489"

    # 5. Dummy number includes support contact message
    from app.core.config import settings
    settings.SUPPORT_CONTACT = "support@example.com"
    ok, err = validate_phone_kyc("+919999999999")
    assert not ok
    assert "support@example.com" in err

    settings.SUPPORT_CONTACT = ""
    ok, err = validate_phone_kyc("+919999999999")
    assert not ok
    assert "please contact your system administrator" in err

    # 6. Hardware verification: Android emulator allowed when DEBUG is True
    settings.DEBUG = True
    ok, err = validate_hardware_kyc("Google sdk_gphone64_x86_64 (Android 14)")
    assert ok and err is None

    # 7. Hardware verification: Android emulator rejected when DEBUG is False
    settings.DEBUG = False
    ok, err = validate_hardware_kyc("Google sdk_gphone64_x86_64 (Android 14)")
    assert not ok
    assert "Android emulators are not permitted for production" in err
    settings.DEBUG = True

    # 8. Hardware verification: dummy string rejected
    ok, err = validate_hardware_kyc("fake")
    assert not ok
    assert "missing or incomplete" in err or "authentic physical Android handset" in err


def test_dns_mx_mocked_resolver_and_fail_open():
    """Verify DNS resolution policy without performing any live network calls."""
    import dns.resolver
    import dns.exception
    from unittest.mock import MagicMock
    from app.core.kyc_validator import check_domain_dns_mx

    # 1. Null MX (RFC 7505 - exchange ".") -> Rejected
    mock_null_mx = MagicMock()
    mock_record = MagicMock()
    mock_record.exchange = "."
    mock_null_mx.resolve.return_value = [mock_record]
    ok, err = check_domain_dns_mx("nullmx.example", resolver=mock_null_mx)
    assert not ok
    assert "Null MX" in err

    # 2. NXDOMAIN (Definitive failure) -> Rejected
    mock_nx = MagicMock()
    mock_nx.resolve.side_effect = dns.resolver.NXDOMAIN()
    ok, err = check_domain_dns_mx("nonexistent.example", resolver=mock_nx)
    assert not ok
    assert "does not exist" in err

    # 3. Timeout error -> Fails open (returns True, None)
    mock_timeout = MagicMock()
    mock_timeout.resolve.side_effect = dns.resolver.Timeout()
    ok, err = check_domain_dns_mx("slowdns.example", resolver=mock_timeout)
    assert ok
    assert err is None

    # 4. Generic DNSException (e.g. NoNameservers / ServerFailure) -> Fails open
    mock_dns_err = MagicMock()
    mock_dns_err.resolve.side_effect = dns.exception.DNSException("Server Failure")
    ok, err = check_domain_dns_mx("failuredns.example", resolver=mock_dns_err)
    assert ok
    assert err is None

    # 5. Valid MX record -> Accepted
    mock_valid_mx = MagicMock()
    valid_record = MagicMock()
    valid_record.exchange = "mail.example.com"
    mock_valid_mx.resolve.return_value = [valid_record]
    ok, err = check_domain_dns_mx("legitimate.example", resolver=mock_valid_mx)
    assert ok
    assert err is None

