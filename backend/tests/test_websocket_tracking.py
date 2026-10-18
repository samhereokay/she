import base64
import pytest
from datetime import datetime, timezone, timedelta
from fastapi.testclient import TestClient
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization

from app.main import app
from app.api.locations import format_canonical_location_string
from conftest import enroll_totp_and_get_2fa_token


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


def test_websocket_tracking_requires_token():
    client = TestClient(app)
    # Should close with 1008 if no token is provided
    with pytest.raises(Exception):
        with client.websocket_connect("/api/v1/ws/devices/dummy-device-id") as ws:
            pass


def test_websocket_tracking_rejects_invalid_token():
    client = TestClient(app)
    with pytest.raises(Exception):
        with client.websocket_connect("/api/v1/ws/devices/dummy-device-id?token=invalid.jwt.token") as ws:
            pass


def test_websocket_tracking_rejects_unauthorized_device():
    client = TestClient(app)
    # 1. Register and login as user
    client.post("/auth/register", json={
        "username": "ws_user_1",
        "email": "ws1@example.com",
        "password": "Password123!Safe"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "ws_user_1",
        "password": "Password123!Safe"
    })
    token = login_resp.json()["access_token"]

    # 2. Try to connect to a non-existent or unowned device
    with pytest.raises(Exception):
        with client.websocket_connect(f"/api/v1/ws/devices/non-existent-device-id?token={token}") as ws:
            pass


def test_websocket_tracking_initial_state_and_ping_pong(test_keypair):
    client = TestClient(app)
    private_key, pem_public = test_keypair

    # 1. Register & Login
    client.post("/auth/register", json={
        "username": "ws_user_2",
        "email": "ws2@example.com",
        "password": "Password123!Safe"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "ws_user_2",
        "password": "Password123!Safe"
    })
    raw_token = login_resp.json()["access_token"]
    token = enroll_totp_and_get_2fa_token(client, raw_token)
    headers = {"Authorization": f"Bearer {token}"}

    # 2. Pair Guardian device
    pt_resp = client.post("/auth/pairing-token", headers=headers, json={})
    pt = pt_resp.json()["pairing_token"]

    pair_resp = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {pt}"},
        json={
            "device_alias": "Live WS Test Phone",
            "public_key_pem": pem_public,
            "trusted_phone_number": "+15550001111",
            "pairing_secret": "secret1234567890abcdef12345678"
        }
    )
    dev_data = pair_resp.json()
    device_id = dev_data["id"]

    # Confirm pairing
    client.post(
        f"/api/v1/devices/{device_id}/confirm-pairing",
        headers=headers,
        json={"confirmation_code": dev_data["confirmation_code"]}
    )

    # 3. Connect over WebSocket
    with client.websocket_connect(f"/api/v1/ws/devices/{device_id}?token={token}") as ws:
        # Initial message must be INITIAL_STATE
        initial = ws.receive_json()
        assert initial["type"] == "INITIAL_STATE"
        assert initial["device_id"] == device_id
        assert initial["device_alias"] == "Live WS Test Phone"
        assert initial["status"] == "ACTIVE"
        assert initial["latest_location"] is None
        assert initial["trail"] == []

        # Send ping, expect pong
        ws.send_text("ping")
        resp = ws.receive_text()
        assert resp == "pong"


def test_websocket_tracking_live_broadcast_on_ingest(test_keypair):
    client = TestClient(app)
    private_key, pem_public = test_keypair

    # 1. Register & Login
    client.post("/auth/register", json={
        "username": "ws_user_3",
        "email": "ws3@example.com",
        "password": "Password123!Safe"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "ws_user_3",
        "password": "Password123!Safe"
    })
    raw_token = login_resp.json()["access_token"]
    token = enroll_totp_and_get_2fa_token(client, raw_token)
    headers = {"Authorization": f"Bearer {token}"}

    # 2. Pair device
    pt_resp = client.post("/auth/pairing-token", headers=headers, json={})
    pt = pt_resp.json()["pairing_token"]

    pair_resp = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {pt}"},
        json={
            "device_alias": "Broadcast Device",
            "public_key_pem": pem_public,
            "trusted_phone_number": "+15550002222",
            "pairing_secret": "broadcastsecret1234567890abcdef"
        }
    )
    dev_data = pair_resp.json()
    device_id = dev_data["id"]

    client.post(
        f"/api/v1/devices/{device_id}/confirm-pairing",
        headers=headers,
        json={"confirmation_code": dev_data["confirmation_code"]}
    )

    # 3. Open WebSocket stream
    with client.websocket_connect(f"/api/v1/ws/devices/{device_id}?token={token}") as ws:
        init_msg = ws.receive_json()
        assert init_msg["type"] == "INITIAL_STATE"

        # 4. Ingest a signed location fix from another client connection
        cap_time = datetime.now(timezone.utc)
        epoch = int(cap_time.timestamp())
        lat = 28.61393
        lng = 77.22950
        acc = 8.5
        seq = 1

        canonical_bytes = format_canonical_location_string(
            device_id=device_id,
            sequence_number=seq,
            capture_timestamp_sec=epoch,
            latitude=lat,
            longitude=lng,
            accuracy=acc
        )
        sig = private_key.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))

        batch_payload = {
            "device_id": device_id,
            "events": [{
                "sequence_number": seq,
                "latitude": lat,
                "longitude": lng,
                "accuracy": acc,
                "velocity": 4.5,
                "altitude": 215.0,
                "capture_time": cap_time.isoformat(),
                "signature": base64.b64encode(sig).decode("utf-8")
            }]
        }

        ingest_resp = client.post("/api/v1/locations/batch", json=batch_payload)
        assert ingest_resp.status_code == 200
        assert ingest_resp.json()["acknowledged_sequences"] == [seq]

        # 5. Verify the WebSocket receives the live broadcast fix
        broadcast_msg = ws.receive_json()
        assert broadcast_msg["type"] == "LOCATION_UPDATE"
        assert broadcast_msg["device_id"] == device_id
        assert abs(broadcast_msg["latitude"] - lat) < 0.00001
        assert abs(broadcast_msg["longitude"] - lng) < 0.00001
        assert broadcast_msg["sequence_number"] == seq
        assert broadcast_msg["accuracy"] == acc
