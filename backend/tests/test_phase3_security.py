import time
import base64
import pytest
from fastapi.testclient import TestClient
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization

from app.main import app
from app.db.session import get_db, Base
from app.db.models import User
from app.core.config import settings
from app.core.security import (
    verify_totp_code,
    create_canonical_command_bytes
)
from tests.conftest import TestingSessionLocal


@pytest.fixture
def client():
    return TestClient(app)


def test_totp_setup_and_verify_flow(client):
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register owner
    reg_resp = client.post("/auth/register", json={
        "username": "owner_2fa",
        "email": "owner_2fa@example.com",
        "password": "SecurePassword123!"
    })
    assert reg_resp.status_code == 201

    # 2. Login with password (gets baseline 1FA token)
    login_resp = client.post("/auth/login/json", json={
        "username": "owner_2fa",
        "password": "SecurePassword123!"
    })
    assert login_resp.status_code == 200
    token_1fa = login_resp.json()["access_token"]
    assert login_resp.json()["is_2fa"] is True  # True initially because totp_enabled=False

    # 3. Setup TOTP
    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {token_1fa}"})
    assert setup_resp.status_code == 200
    totp_secret = setup_resp.json()["secret"]
    assert "otpauth_uri" in setup_resp.json()

    # 4. Try verify with invalid code
    bad_verify = client.post("/auth/totp/verify", json={"code": "000000"}, headers={"Authorization": f"Bearer {token_1fa}"})
    assert bad_verify.status_code == 400

    # 5. Compute valid TOTP code
    # Generate code using Python struct/hmac
    import struct, hmac, hashlib
    key = base64.b32decode(totp_secret + "=" * ((8 - len(totp_secret) % 8) % 8), casefold=True)
    counter = int(time.time() // 30)
    msg = struct.pack(">Q", counter)
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
    valid_code = f"{code_int % 1000000:06d}"

    verify_resp = client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {token_1fa}"})
    assert verify_resp.status_code == 200
    token_2fa = verify_resp.json()["access_token"]
    assert verify_resp.json()["is_2fa"] is True

    # 6. Subsequent login now returns 1FA token because TOTP is enabled!
    subsequent_login = client.post("/auth/login/json", json={
        "username": "owner_2fa",
        "password": "SecurePassword123!"
    })
    assert subsequent_login.status_code == 200
    assert subsequent_login.json()["is_2fa"] is False  # Requires 2FA step!
    token_needs_2fa = subsequent_login.json()["access_token"]

    # 7. Validate code to upgrade session to 2FA
    upgrade_resp = client.post("/auth/totp/validate", json={"code": valid_code}, headers={"Authorization": f"Bearer {token_needs_2fa}"})
    assert upgrade_resp.status_code == 200
    assert upgrade_resp.json()["is_2fa"] is True


def test_command_dispatch_requires_2fa_and_admin_signature(client):
    # Generate Admin ECDSA Keypair
    admin_priv = ec.generate_private_key(ec.SECP256R1())
    admin_pub_pem = admin_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    # Generate Guardian Keypair
    guardian_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_pub_pem = guardian_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    # Reset rate limiters for test isolation
    from app.core.rate_limit import auth_limiter, batch_ingest_limiter
    auth_limiter.history.clear()
    batch_ingest_limiter.history.clear()

    # Register owner
    settings.ALLOW_PUBLIC_REGISTRATION = True
    reg_resp = client.post("/auth/register", json={
        "username": "owner_dispatch",
        "email": "owner_dispatch@example.com",
        "password": "SecurePassword123!"
    })
    assert reg_resp.status_code == 201, reg_resp.text

    # Login with owner_dispatch
    login_resp = client.post("/auth/login/json", json={
        "username": "owner_dispatch",
        "password": "SecurePassword123!"
    })
    assert login_resp.status_code == 200, login_resp.text
    initial_token = login_resp.json()["access_token"]

    # Setup TOTP
    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {initial_token}"})
    totp_secret = setup_resp.json()["secret"]

    import struct, hmac, hashlib
    key = base64.b32decode(totp_secret + "=" * ((8 - len(totp_secret) % 8) % 8), casefold=True)
    counter = int(time.time() // 30)
    msg = struct.pack(">Q", counter)
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
    valid_code = f"{code_int % 1000000:06d}"

    verify_resp = client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {initial_token}"})
    assert verify_resp.status_code == 200

    # Subsequent login gets 1FA token (password only, is_2fa=False)
    login_1fa_resp = client.post("/auth/login/json", json={
        "username": "owner_dispatch",
        "password": "SecurePassword123!"
    })
    token_1fa = login_1fa_resp.json()["access_token"]
    assert login_1fa_resp.json()["is_2fa"] is False

    # 1. Pair a protected device
    pair_resp = client.post("/api/v1/devices/pair", json={
        "device_alias": "TargetPhone",
        "public_key_pem": guardian_pub_pem,
        "trusted_phone_number": "+15551234567",
        "pairing_secret": "0123456789ABCDEF0123456789ABCDEF"
    }, headers={"Authorization": f"Bearer {token_1fa}"})
    assert pair_resp.status_code == 201
    target_device_id = pair_resp.json()["id"]

    # 2. Register trusted admin device
    admin_reg = client.post("/api/v1/devices/trusted", json={
        "device_name": "Owner Pixel Admin",
        "public_key_pem": admin_pub_pem
    }, headers={"Authorization": f"Bearer {token_1fa}"})
    assert admin_reg.status_code == 201
    trusted_device_id = admin_reg.json()["id"]

    # 3. Create command signature
    seq = 1
    expiry = int(time.time()) + 300
    cmd_type = "RING"
    params = '{"volume":1.0,"duration_seconds":60}'

    canonical_bytes = create_canonical_command_bytes(
        command_id="",
        target_device_id=target_device_id,
        command_type=cmd_type,
        sequence_counter=seq,
        expiry_epoch=expiry,
        parameters_json=params
    )
    sig_der = admin_priv.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))
    sig_b64 = base64.b64encode(sig_der).decode("utf-8")

    # 4. Attempt dispatch with 1FA token -> MUST BE REJECTED with 403
    rejected_dispatch = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_device_id,
        "target_device_id": target_device_id,
        "command_type": cmd_type,
        "sequence_counter": seq,
        "expiry_epoch": expiry,
        "parameters_json": params,
        "admin_signature": sig_b64
    }, headers={"Authorization": f"Bearer {token_1fa}"})
    assert rejected_dispatch.status_code == 403
    assert "Two-factor authentication" in rejected_dispatch.json()["detail"]

    # 5. Upgrade token to 2FA using TOTP
    upgrade_resp = client.post("/auth/totp/validate", json={"code": valid_code}, headers={"Authorization": f"Bearer {token_1fa}"})
    assert upgrade_resp.status_code == 200
    token_2fa = upgrade_resp.json()["access_token"]

    # 6. Dispatch with 2FA token -> SUCCEEDS (201)
    dispatch_resp = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_device_id,
        "target_device_id": target_device_id,
        "command_type": cmd_type,
        "sequence_counter": seq,
        "expiry_epoch": expiry,
        "parameters_json": params,
        "admin_signature": sig_b64
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    assert dispatch_resp.status_code == 201
    cmd_id = dispatch_resp.json()["id"]
    assert dispatch_resp.json()["status"] == "PENDING"

    # 7. Replayed sequence number -> MUST BE REJECTED with 409
    replayed_dispatch = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_device_id,
        "target_device_id": target_device_id,
        "command_type": cmd_type,
        "sequence_counter": seq,
        "expiry_epoch": expiry,
        "parameters_json": params,
        "admin_signature": sig_b64
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    assert replayed_dispatch.status_code == 409

    # 8. Guardian polls for pending commands
    poll_resp = client.get(f"/api/v1/devices/{target_device_id}/commands/pending")
    assert poll_resp.status_code == 200
    pending_list = poll_resp.json()
    assert len(pending_list) == 1
    assert pending_list[0]["id"] == cmd_id
    assert pending_list[0]["command_type"] == "RING"
    assert pending_list[0]["admin_signature"] == sig_b64

    # 9. Guardian acknowledges command
    ack_resp = client.post(f"/api/v1/devices/{target_device_id}/commands/{cmd_id}/ack")
    assert ack_resp.status_code == 200
    assert ack_resp.json()["status"] == "success"

    # Verify no more pending commands
    poll_resp_after = client.get(f"/api/v1/devices/{target_device_id}/commands/pending")
    assert len(poll_resp_after.json()) == 0
