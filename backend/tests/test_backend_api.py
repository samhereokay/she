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
from app.core.security import compute_truncated_hmac, verify_truncated_hmac

# Setup in-memory SQLite database for isolated test execution
SQLALCHEMY_DATABASE_URL = "sqlite:///:memory:"

engine = create_engine(
    SQLALCHEMY_DATABASE_URL,
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def override_get_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


app.dependency_overrides[get_db] = override_get_db

client = TestClient(app)


@pytest.fixture(autouse=True)
def setup_database():
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


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
    headers = {"Authorization": f"Bearer {token}"}

    # Pair Guardian phone
    pair_resp = client.post("/api/v1/devices/pair", headers=headers, json={
        "device_alias": "Bob Pixel 8",
        "public_key_pem": pem_public,
        "trusted_phone_number": "+15551234567",
        "pairing_secret": "48f12c8b90a4e76d1234567890abcdef"
    })
    assert pair_resp.status_code == 201
    device_data = pair_resp.json()
    assert device_data["device_alias"] == "Bob Pixel 8"
    assert device_data["status"] == "ACTIVE"
    assert device_data["calculated_status"] == "UNAVAILABLE"  # No location yet

    # List devices
    list_resp = client.get("/api/v1/devices", headers=headers)
    assert list_resp.status_code == 200
    devices = list_resp.json()["devices"]
    assert len(devices) == 1
    assert devices[0]["id"] == device_data["id"]


def test_location_batch_ingest_and_signature_verification(test_keypair):
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

    device_id = client.post("/api/v1/devices/pair", headers=headers, json={
        "device_alias": "Carol Phone",
        "public_key_pem": pem_public,
        "trusted_phone_number": "+15559876543",
        "pairing_secret": "secretpairingkey1234567890abcdef"
    }).json()["id"]

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

    # 4. Check device status is now LIVE
    dev_resp = client.get(f"/api/v1/devices/{device_id}", headers=headers)
    assert dev_resp.status_code == 200
    assert dev_resp.json()["calculated_status"] == "LIVE"
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


def test_idempotent_offline_queue_reconnect(test_keypair):
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

    device_id = client.post("/api/v1/devices/pair", headers=headers, json={
        "device_alias": "Dave Offline Phone",
        "public_key_pem": pem_public,
        "trusted_phone_number": "+15553334444",
        "pairing_secret": "davesecret1234567890abcdef123456"
    }).json()["id"]

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


def test_sms_48bit_truncated_hmac():
    """Verify 48-bit truncated HMAC calculation and constant-time verification."""
    key = b"test_symmetric_pairing_key_256bit!"
    msg = b"ST:G1|37.77492,-122.41941|14|1729036800|104"
    
    hmac12 = compute_truncated_hmac(key, msg, hex_length=12)
    assert len(hmac12) == 12  # Exactly 12 hex characters = 48 bits
    assert verify_truncated_hmac(key, msg, hmac12) is True
    
    # Tampered message must fail
    tampered_msg = b"ST:G1|37.77492,-122.41941|14|1729036800|105"
    assert verify_truncated_hmac(key, tampered_msg, hmac12) is False
    
    # Tampered key must fail
    assert verify_truncated_hmac(b"wrong_key", msg, hmac12) is False
