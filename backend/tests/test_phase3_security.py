import time
import base64
import pytest
from fastapi.testclient import TestClient
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization

from app.main import app
from app.db.session import get_db, Base
from app.db.models import User, AuditEvent, DeviceCommand
from app.core.config import settings
from app.core.security import (
    verify_totp_code,
    decrypt_totp_secret,
    create_canonical_command_bytes
)
from app.core.rate_limit import totp_limiter, auth_limiter


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
    assert login_resp.json()["is_2fa"] is False  # Password login is strictly 1FA

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


def test_command_dispatch_requires_2fa_and_admin_signature(client, pair_device):
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
    token_2fa = verify_resp.json()["access_token"]

    # Subsequent login gets 1FA token (password only, is_2fa=False)
    login_1fa_resp = client.post("/auth/login/json", json={
        "username": "owner_dispatch",
        "password": "SecurePassword123!"
    })
    token_1fa = login_1fa_resp.json()["access_token"]
    assert login_1fa_resp.json()["is_2fa"] is False

    # 1. Pair a protected device using 2FA session
    target_device_id, pairing_id = pair_device(
        client,
        token_2fa,
        "TargetPhone",
        guardian_pub_pem,
        "+15551234567",
        "0123456789ABCDEF0123456789ABCDEF"
    )

    # 2. Register trusted admin device
    admin_reg = client.post("/api/v1/devices/trusted", json={
        "device_name": "Owner Pixel Admin",
        "public_key_pem": admin_pub_pem
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    assert admin_reg.status_code == 201
    trusted_device_id = admin_reg.json()["id"]

    # 3. Create command signature with pairing_id
    seq = 1
    expiry = int(time.time()) + 300
    cmd_type = "RING"
    params = '{"volume":1.0,"duration_seconds":60}'

    canonical_bytes = create_canonical_command_bytes(
        pairing_id=pairing_id,
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

    # 5. Dispatch with 2FA token -> SUCCEEDS (201)
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
    assert dispatch_resp.json()["pairing_id"] == pairing_id

    # 6. Replayed sequence number -> MUST BE REJECTED with 409
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

    # 7. Guardian polls for pending commands
    poll_resp = client.get(f"/api/v1/devices/{target_device_id}/commands/pending")
    assert poll_resp.status_code == 200
    pending_list = poll_resp.json()
    assert len(pending_list) == 1
    assert pending_list[0]["id"] == cmd_id
    assert pending_list[0]["command_type"] == "RING"
    assert pending_list[0]["admin_signature"] == sig_b64
    assert pending_list[0]["pairing_id"] == pairing_id

    # 8. Guardian acknowledges command
    ack_resp = client.post(f"/api/v1/devices/{target_device_id}/commands/{cmd_id}/ack")
    assert ack_resp.status_code == 200
    assert ack_resp.json()["status"] == "success"

    # Verify no more pending commands
    poll_resp_after = client.get(f"/api/v1/devices/{target_device_id}/commands/pending")
    assert len(poll_resp_after.json()) == 0


def test_password_only_token_cannot_dispatch_command(client, pair_device):
    """Enforces that 1FA (password-only) sessions return HTTP 403 when attempting to dispatch commands."""
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register owner and setup TOTP
    client.post("/auth/register", json={
        "username": "pwd_only_user",
        "email": "pwd_only@example.com",
        "password": "SecurePassword123!"
    })
    init_token = client.post("/auth/login/json", json={
        "username": "pwd_only_user",
        "password": "SecurePassword123!"
    }).json()["access_token"]

    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {init_token}"})
    secret = setup_resp.json()["secret"]

    import struct, hmac, hashlib
    key = base64.b32decode(secret + "=" * ((8 - len(secret) % 8) % 8), casefold=True)
    msg = struct.pack(">Q", int(time.time() // 30))
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
    valid_code = f"{code_int % 1000000:06d}"

    verify_resp = client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {init_token}"})
    token_2fa = verify_resp.json()["access_token"]

    # 2. Pair device and register trusted device using 2FA session
    admin_priv = ec.generate_private_key(ec.SECP256R1())
    admin_pub = admin_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    guardian_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_pub = guardian_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    target_id, pairing_id = pair_device(
        client,
        token_2fa,
        "ProtectedPhone",
        guardian_pub,
        "+15550001111",
        "A1B2C3D4E5F60123A1B2C3D4E5F60123"
    )

    admin_resp = client.post("/api/v1/devices/trusted", json={
        "device_name": "Admin Pixel",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    assert admin_resp.status_code == 201
    trusted_id = admin_resp.json()["id"]

    # 3. Login again -> returns password-only 1FA token (is_2fa=False)
    pwd_login = client.post("/auth/login/json", json={
        "username": "pwd_only_user",
        "password": "SecurePassword123!"
    })
    pwd_token = pwd_login.json()["access_token"]
    assert pwd_login.json()["is_2fa"] is False

    # 4. Sign a LOCK command with valid pairing_id
    now_epoch = int(time.time())
    canonical_bytes = create_canonical_command_bytes(
        pairing_id=pairing_id,
        command_id="",
        target_device_id=target_id,
        command_type="LOCK",
        sequence_counter=1,
        expiry_epoch=now_epoch + 300,
        parameters_json="{}"
    )
    sig = base64.b64encode(admin_priv.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    # 5. Dispatch command with 1FA token -> MUST FAIL WITH 403 FORBIDDEN
    dispatch_resp = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_id,
        "target_device_id": target_id,
        "command_type": "LOCK",
        "sequence_counter": 1,
        "expiry_epoch": now_epoch + 300,
        "parameters_json": "{}",
        "admin_signature": sig
    }, headers={"Authorization": f"Bearer {pwd_token}"})

    assert dispatch_resp.status_code == 403
    assert "Two-factor authentication" in dispatch_resp.json()["detail"]


def test_wrong_totp_rejected_and_rate_limited(client):
    """Enforces that invalid TOTP codes return HTTP 401 and >5 attempts trigger HTTP 429 rate limit."""
    auth_limiter.history.clear()
    totp_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register and setup TOTP
    client.post("/auth/register", json={
        "username": "rate_limit_user",
        "email": "rl@example.com",
        "password": "SecurePassword123!"
    })
    init_token = client.post("/auth/login/json", json={
        "username": "rate_limit_user",
        "password": "SecurePassword123!"
    }).json()["access_token"]

    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {init_token}"})
    secret = setup_resp.json()["secret"]

    import struct, hmac, hashlib
    key = base64.b32decode(secret + "=" * ((8 - len(secret) % 8) % 8), casefold=True)
    msg = struct.pack(">Q", int(time.time() // 30))
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
    valid_code = f"{code_int % 1000000:06d}"

    client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {init_token}"})

    # 2. Login to get 1FA token
    token_1fa = client.post("/auth/login/json", json={
        "username": "rate_limit_user",
        "password": "SecurePassword123!"
    }).json()["access_token"]

    # Clear rate limiter history so validate attempts start at 0
    totp_limiter.history.clear()

    # 3. Send 5 wrong TOTP attempts -> all should return 401
    for attempt in range(5):
        wrong_resp = client.post(
            "/auth/totp/validate",
            json={"code": "000000"},
            headers={"Authorization": f"Bearer {token_1fa}"}
        )
        assert wrong_resp.status_code == 401, f"Attempt {attempt+1} should return 401, got {wrong_resp.status_code}"
        assert "Invalid TOTP" in wrong_resp.json()["detail"]

    # 4. 6th attempt -> must be blocked by rate limiter with 429
    blocked_resp = client.post(
        "/auth/totp/validate",
        json={"code": "000000"},
        headers={"Authorization": f"Bearer {token_1fa}"}
    )
    assert blocked_resp.status_code == 429
    assert "Rate limit exceeded" in blocked_resp.json()["detail"]


def test_totp_secret_encrypted_at_rest_in_db(client, db_session):
    """Enforces that User.totp_secret is stored as encrypted ciphertext in the DB, not plaintext base32."""
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    username = "crypto_at_rest_user"
    client.post("/auth/register", json={
        "username": username,
        "email": "car@example.com",
        "password": "SecurePassword123!"
    })
    token = client.post("/auth/login/json", json={
        "username": username,
        "password": "SecurePassword123!"
    }).json()["access_token"]

    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {token}"})
    assert setup_resp.status_code == 200
    plain_secret = setup_resp.json()["secret"]

    # Query DB directly using the test db_session fixture
    user = db_session.query(User).filter(User.username == username).first()
    assert user is not None
    # Secret stored in SQLite must NOT be the plaintext base32 string!
    assert user.totp_secret != plain_secret
    # Must be valid base64 with 12 bytes IV + 16 bytes GCM tag + ciphertext
    raw_bytes = base64.b64decode(user.totp_secret)
    assert len(raw_bytes) >= 28
    # Decrypting with server secret key must yield the exact original plaintext
    decrypted = decrypt_totp_secret(user.totp_secret)
    assert decrypted == plain_secret


def test_guardian_signed_polling_and_rejection_ack(client, db_session, pair_device):
    """Verifies that command polling requires signed Guardian headers, and Guardian can send signed REJECTED ACKs."""
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Setup user with 2FA
    client.post("/auth/register", json={
        "username": "guardian_poll_user",
        "email": "poll@example.com",
        "password": "SecurePassword123!"
    })
    init_tok = client.post("/auth/login/json", json={
        "username": "guardian_poll_user",
        "password": "SecurePassword123!"
    }).json()["access_token"]

    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {init_tok}"})
    secret = setup_resp.json()["secret"]

    import struct, hmac, hashlib
    key = base64.b32decode(secret + "=" * ((8 - len(secret) % 8) % 8), casefold=True)
    msg = struct.pack(">Q", int(time.time() // 30))
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
    valid_code = f"{code_int % 1000000:06d}"

    v_resp = client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {init_tok}"})
    token_2fa = v_resp.json()["access_token"]

    # 2. Create Guardian and Admin keys
    guardian_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_pub = guardian_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    admin_priv = ec.generate_private_key(ec.SECP256R1())
    admin_pub = admin_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    target_id, pairing_id = pair_device(
        client,
        token_2fa,
        "PollTarget",
        guardian_pub,
        "+15558889999",
        "11223344556677881122334455667788"
    )

    admin_resp = client.post("/api/v1/devices/trusted", json={
        "device_name": "AdminController",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    trusted_id = admin_resp.json()["id"]

    # 3. Admin dispatches command with specific command_id
    now_epoch = int(time.time())
    custom_cmd_id = "test-cmd-uuid-12345"
    canonical_bytes = create_canonical_command_bytes(
        pairing_id=pairing_id,
        command_id=custom_cmd_id,
        target_device_id=target_id,
        command_type="LOCK",
        sequence_counter=1,
        expiry_epoch=now_epoch + 300,
        parameters_json="{}"
    )
    sig = base64.b64encode(admin_priv.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    dispatch = client.post("/api/v1/devices/commands/dispatch", json={
        "command_id": custom_cmd_id,
        "trusted_device_id": trusted_id,
        "target_device_id": target_id,
        "command_type": "LOCK",
        "sequence_counter": 1,
        "expiry_epoch": now_epoch + 300,
        "parameters_json": "{}",
        "admin_signature": sig
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    assert dispatch.status_code == 201
    assert dispatch.json()["id"] == custom_cmd_id
    assert dispatch.json()["pairing_id"] == pairing_id

    # 4. Guardian polls:
    original_debug = settings.DEBUG
    settings.DEBUG = False
    try:
        # 4a. Poll without signature headers -> 401 Unauthorized
        unauth_poll = client.get(f"/api/v1/devices/{target_id}/commands/pending")
        assert unauth_poll.status_code == 401

        # 4b. Poll with expired timestamp (>300s old) -> 401
        expired_ts = now_epoch - 400
        expired_poll_bytes = f"POLL_COMMANDS|{target_id}|{expired_ts}".encode("utf-8")
        expired_sig = base64.b64encode(guardian_priv.sign(expired_poll_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")
        exp_poll = client.get(f"/api/v1/devices/{target_id}/commands/pending", headers={
            "X-Device-Timestamp": str(expired_ts),
            "X-Device-Signature": expired_sig
        })
        assert exp_poll.status_code == 401
        assert "expired" in exp_poll.json()["detail"]

        # 4c. Poll with invalid signature -> 401
        bad_poll = client.get(f"/api/v1/devices/{target_id}/commands/pending", headers={
            "X-Device-Timestamp": str(now_epoch),
            "X-Device-Signature": base64.b64encode(b"invalid_signature_bytes").decode("utf-8")
        })
        assert bad_poll.status_code == 401

        # 4d. Valid signed poll -> 200 OK
        valid_poll_bytes = f"POLL_COMMANDS|{target_id}|{now_epoch}".encode("utf-8")
        valid_sig = base64.b64encode(guardian_priv.sign(valid_poll_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")
        poll_ok = client.get(f"/api/v1/devices/{target_id}/commands/pending", headers={
            "X-Device-Timestamp": str(now_epoch),
            "X-Device-Signature": valid_sig
        })
        assert poll_ok.status_code == 200
        cmds = poll_ok.json()
        assert len(cmds) == 1
        assert cmds[0]["id"] == custom_cmd_id
        assert cmds[0]["pairing_id"] == pairing_id
    finally:
        settings.DEBUG = original_debug

    # 5. Guardian rejects command with reason
    ack_reject = client.post(f"/api/v1/devices/{target_id}/commands/{custom_cmd_id}/ack", json={
        "status": "REJECTED",
        "reason": "POLICY_DEVICE_ADMIN_NOT_ACTIVE"
    })
    assert ack_reject.status_code == 200
    assert ack_reject.json()["command_status"] == "REJECTED"

    # 6. Verify audit event was logged in DB
    cmd = db_session.query(DeviceCommand).filter(DeviceCommand.id == custom_cmd_id).first()
    assert cmd.status == "REJECTED"

    audit = db_session.query(AuditEvent).filter(
        AuditEvent.protected_device_id == target_id,
        AuditEvent.event_type == "COMMAND_REJECTED_BY_DEVICE"
    ).first()
    assert audit is not None
    assert "POLICY_DEVICE_ADMIN_NOT_ACTIVE" in audit.details


def test_server_side_command_auto_expiry(client, db_session, pair_device, get_2fa_token):
    """Enforces that past-expiry commands are rejected at dispatch and overdue pending commands auto-expire on poll."""
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register & login
    client.post("/auth/register", json={
        "username": "expiry_user",
        "email": "exp@example.com",
        "password": "SecurePassword123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "expiry_user",
        "password": "SecurePassword123!"
    }).json()["access_token"]
    token = get_2fa_token(client, token)

    # 2. Keys & Pairing
    guardian_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_pub = guardian_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    admin_priv = ec.generate_private_key(ec.SECP256R1())
    admin_pub = admin_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    target_id, pairing_id = pair_device(
        client,
        token,
        "ExpTarget",
        guardian_pub,
        "+15557776666",
        "33445566778899003344556677889900"
    )

    trusted_id = client.post("/api/v1/devices/trusted", json={
        "device_name": "AdminExp",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {token}"}).json()["id"]

    # 3. Create command with expiry in the PAST -> MUST BE REJECTED WITH 400
    past_epoch = int(time.time()) - 60
    cmd_bytes_past = create_canonical_command_bytes(pairing_id, "", target_id, "RING", 1, past_epoch, "{}")
    sig_past = base64.b64encode(admin_priv.sign(cmd_bytes_past, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    past_dispatch = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_id,
        "target_device_id": target_id,
        "command_type": "RING",
        "sequence_counter": 1,
        "expiry_epoch": past_epoch,
        "parameters_json": "{}",
        "admin_signature": sig_past
    }, headers={"Authorization": f"Bearer {token}"})
    assert past_dispatch.status_code == 400
    assert "expired" in past_dispatch.json()["detail"].lower()

    # 4. Dispatch valid command that expires very soon (in 1 second)
    soon_epoch = int(time.time()) + 1
    cmd_bytes_soon = create_canonical_command_bytes(pairing_id, "", target_id, "RING", 2, soon_epoch, "{}")
    sig_soon = base64.b64encode(admin_priv.sign(cmd_bytes_soon, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    valid_dispatch = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_id,
        "target_device_id": target_id,
        "command_type": "RING",
        "sequence_counter": 2,
        "expiry_epoch": soon_epoch,
        "parameters_json": "{}",
        "admin_signature": sig_soon
    }, headers={"Authorization": f"Bearer {token}"})
    assert valid_dispatch.status_code == 201
    cmd_id = valid_dispatch.json()["id"]

    # Wait for the command to expire on the server clock
    time.sleep(1.2)

    # 5. Polling after expiry should return 0 pending commands and transition status to EXPIRED
    poll_resp = client.get(f"/api/v1/devices/{target_id}/commands/pending")
    assert poll_resp.status_code == 200
    assert len(poll_resp.json()) == 0

    # 6. Verify in DB
    cmd = db_session.query(DeviceCommand).filter(DeviceCommand.id == cmd_id).first()
    assert cmd is not None
    assert cmd.status == "EXPIRED"


def test_pairing_token_single_use_and_session_token_rejected(client, get_2fa_token):
    """Enforces that pairing tokens are single-use, session tokens cannot pair devices, and pairing tokens cannot access session APIs."""
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # Register and login owner
    client.post("/auth/register", json={
        "username": "pairing_token_user",
        "email": "ptu@example.com",
        "password": "SecurePassword123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "pairing_token_user",
        "password": "SecurePassword123!"
    }).json()["access_token"]

    # 1. Attempting to pair directly with a regular session token MUST BE REJECTED with 401
    guardian_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_pub = guardian_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    session_pair_attempt = client.post("/api/v1/devices/pair", json={
        "device_alias": "SessionPairAttempt",
        "public_key_pem": guardian_pub,
        "trusted_phone_number": "+15551112222",
        "pairing_secret": "AABBCCDDEEFF00112233445566778899"
    }, headers={"Authorization": f"Bearer {token}"})
    assert session_pair_attempt.status_code == 401
    assert "pairing token" in session_pair_attempt.json()["detail"].lower()

    # 2. Issue a restricted pairing token (requires 2FA enrollment)
    token_2fa = get_2fa_token(client, token)
    pt_resp = client.post("/auth/pairing-token", json={}, headers={"Authorization": f"Bearer {token_2fa}"})
    assert pt_resp.status_code == 200
    pairing_token = pt_resp.json()["pairing_token"]

    # 3. Pairing token CANNOT access session endpoints (e.g. /auth/me)
    unauth_me = client.get("/auth/me", headers={"Authorization": f"Bearer {pairing_token}"})
    assert unauth_me.status_code == 403

    # 4. Use pairing token to enroll Guardian -> SUCCEEDS (201, status PENDING_CONFIRMATION)
    enroll_resp = client.post("/api/v1/devices/pair", json={
        "device_alias": "LegitGuardian",
        "public_key_pem": guardian_pub,
        "trusted_phone_number": "+15551112222",
        "pairing_secret": "AABBCCDDEEFF00112233445566778899"
    }, headers={"Authorization": f"Bearer {pairing_token}"})
    assert enroll_resp.status_code == 201
    device_data = enroll_resp.json()
    assert device_data["status"] == "PENDING_CONFIRMATION"
    assert "confirmation_code" in device_data

    # 5. Attempt to reuse the SAME pairing token -> MUST BE REJECTED with 401 (single-use JTI consumed)
    reused_resp = client.post("/api/v1/devices/pair", json={
        "device_alias": "ReusedAttempt",
        "public_key_pem": guardian_pub,
        "trusted_phone_number": "+15551112222",
        "pairing_secret": "AABBCCDDEEFF00112233445566778899"
    }, headers={"Authorization": f"Bearer {pairing_token}"})
    assert reused_resp.status_code == 401
    assert "consumed" in reused_resp.json()["detail"].lower()


def test_revoked_trusted_admin_device_cannot_dispatch(client, pair_device, get_2fa_token):
    """Enforces that revoking an admin device immediately blocks command dispatch signed by it."""
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # Setup owner
    client.post("/auth/register", json={
        "username": "revoke_admin_user",
        "email": "rau@example.com",
        "password": "SecurePassword123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "revoke_admin_user",
        "password": "SecurePassword123!"
    }).json()["access_token"]
    token = get_2fa_token(client, token)

    # Register admin device
    admin_priv = ec.generate_private_key(ec.SECP256R1())
    admin_pub = admin_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    trusted_resp = client.post("/api/v1/devices/trusted", json={
        "device_name": "SoonRevokedAdmin",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {token}"})
    assert trusted_resp.status_code == 201
    trusted_id = trusted_resp.json()["id"]

    # Pair guardian
    guardian_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_pub = guardian_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    target_id, pairing_id = pair_device(
        client,
        token,
        "RevokeTarget",
        guardian_pub,
        "+15552223333",
        "12121212121212121212121212121212"
    )

    # Revoke the admin device
    revoke_resp = client.post(f"/auth/trusted-devices/{trusted_id}/revoke", headers={"Authorization": f"Bearer {token}"})
    assert revoke_resp.status_code == 200
    assert revoke_resp.json()["status"] == "REVOKED"

    # Try dispatching command signed by revoked admin -> 403 Forbidden
    now_epoch = int(time.time())
    canonical_bytes = create_canonical_command_bytes(
        pairing_id=pairing_id,
        command_id="",
        target_device_id=target_id,
        command_type="LOCK",
        sequence_counter=1,
        expiry_epoch=now_epoch + 300,
        parameters_json="{}"
    )
    sig = base64.b64encode(admin_priv.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    dispatch_resp = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_id,
        "target_device_id": target_id,
        "command_type": "LOCK",
        "sequence_counter": 1,
        "expiry_epoch": now_epoch + 300,
        "parameters_json": "{}",
        "admin_signature": sig
    }, headers={"Authorization": f"Bearer {token}"})

    assert dispatch_resp.status_code == 403
    assert "revoked" in dispatch_resp.json()["detail"].lower()


def test_cross_user_command_dispatch_rejected(client, pair_device, get_2fa_token):
    """Enforces that User A cannot dispatch commands to User B's device (HTTP 404/403)."""
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Setup User A
    client.post("/auth/register", json={
        "username": "user_a",
        "email": "usera@example.com",
        "password": "SecurePassword123!"
    })
    token_a = client.post("/auth/login/json", json={
        "username": "user_a",
        "password": "SecurePassword123!"
    }).json()["access_token"]
    token_a = get_2fa_token(client, token_a)

    admin_a_priv = ec.generate_private_key(ec.SECP256R1())
    admin_a_pub = admin_a_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    trusted_a_id = client.post("/api/v1/devices/trusted", json={
        "device_name": "AdminA",
        "public_key_pem": admin_a_pub
    }, headers={"Authorization": f"Bearer {token_a}"}).json()["id"]

    guardian_a_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_a_pub = guardian_a_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    device_a_id, pairing_a_id = pair_device(
        client,
        token_a,
        "DeviceA",
        guardian_a_pub,
        "+15551110000",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    )

    # 2. Setup User B
    client.post("/auth/register", json={
        "username": "user_b",
        "email": "userb@example.com",
        "password": "SecurePassword123!"
    })
    token_b = client.post("/auth/login/json", json={
        "username": "user_b",
        "password": "SecurePassword123!"
    }).json()["access_token"]
    token_b = get_2fa_token(client, token_b)

    guardian_b_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_b_pub = guardian_b_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    device_b_id, pairing_b_id = pair_device(
        client,
        token_b,
        "DeviceB",
        guardian_b_pub,
        "+15552220000",
        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    )

    # 3. User A attempts to dispatch command targeting Device B -> MUST BE 404 (Not Found for user)
    now_epoch = int(time.time())
    canonical_bytes = create_canonical_command_bytes(
        pairing_id=pairing_b_id,
        command_id="",
        target_device_id=device_b_id,
        command_type="LOCK",
        sequence_counter=1,
        expiry_epoch=now_epoch + 300,
        parameters_json="{}"
    )
    sig_a = base64.b64encode(admin_a_priv.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    cross_dispatch = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_a_id,
        "target_device_id": device_b_id,
        "command_type": "LOCK",
        "sequence_counter": 1,
        "expiry_epoch": now_epoch + 300,
        "parameters_json": "{}",
        "admin_signature": sig_a
    }, headers={"Authorization": f"Bearer {token_a}"})

    assert cross_dispatch.status_code == 403
    assert "not authorized" in cross_dispatch.json()["detail"].lower()


def test_pairing_id_required_in_command_signature(client, pair_device, get_2fa_token):
    """Enforces that command signature verification strictly binds to target's active pairing_id."""
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # Setup owner
    client.post("/auth/register", json={
        "username": "pairing_binding_user",
        "email": "pbu@example.com",
        "password": "SecurePassword123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "pairing_binding_user",
        "password": "SecurePassword123!"
    }).json()["access_token"]
    token = get_2fa_token(client, token)

    admin_priv = ec.generate_private_key(ec.SECP256R1())
    admin_pub = admin_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    trusted_id = client.post("/api/v1/devices/trusted", json={
        "device_name": "AdminBinding",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {token}"}).json()["id"]

    guardian_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_pub = guardian_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    target_id, actual_pairing_id = pair_device(
        client,
        token,
        "BindingDevice",
        guardian_pub,
        "+15553334444",
        "44556677889900114455667788990011"
    )

    now_epoch = int(time.time())

    # Sign with a WRONG or STALE pairing_id
    wrong_pairing_id = "stale-pairing-id-from-old-session"
    stale_canonical_bytes = create_canonical_command_bytes(
        pairing_id=wrong_pairing_id,
        command_id="",
        target_device_id=target_id,
        command_type="LOCK",
        sequence_counter=1,
        expiry_epoch=now_epoch + 300,
        parameters_json="{}"
    )
    stale_sig = base64.b64encode(admin_priv.sign(stale_canonical_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    # Backend reconstructs canonical bytes using target.pairing_id (actual_pairing_id) -> signature check FAILS
    stale_dispatch = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_id,
        "target_device_id": target_id,
        "command_type": "LOCK",
        "sequence_counter": 1,
        "expiry_epoch": now_epoch + 300,
        "parameters_json": "{}",
        "admin_signature": stale_sig
    }, headers={"Authorization": f"Bearer {token}"})

    assert stale_dispatch.status_code == 401
    assert "verification failed" in stale_dispatch.json()["detail"].lower()


def test_clear_device_owner_command_dispatch_and_execution(client, pair_device):
    """Verifies that the signed CLEAR_DEVICE_OWNER recovery command is accepted and queued."""
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # Setup owner
    client.post("/auth/register", json={
        "username": "deprovision_owner",
        "email": "deprovision_owner@example.com",
        "password": "SecurePassword123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "deprovision_owner",
        "password": "SecurePassword123!"
    }).json()["access_token"]

    # Setup TOTP
    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {token}"})
    totp_secret = setup_resp.json()["secret"]

    import struct, hmac, hashlib
    key = base64.b32decode(totp_secret + "=" * ((8 - len(totp_secret) % 8) % 8), casefold=True)
    counter = int(time.time() // 30)
    msg = struct.pack(">Q", counter)
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
    valid_code = f"{code_int % 1000000:06d}"

    verify_resp = client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {token}"})
    token_2fa = verify_resp.json()["access_token"]

    admin_priv = ec.generate_private_key(ec.SECP256R1())
    admin_pub = admin_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    trusted_id = client.post("/api/v1/devices/trusted", json={
        "device_name": "AdminDeprovision",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {token_2fa}"}).json()["id"]

    guardian_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_pub = guardian_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    enroll_resp = client.post("/auth/pairing-token", json={}, headers={"Authorization": f"Bearer {token_2fa}"})
    p_token = enroll_resp.json()["pairing_token"]

    pair_resp = client.post("/api/v1/devices/pair", json={
        "device_alias": "GuardianDO",
        "public_key_pem": guardian_pub,
        "trusted_phone_number": "+15551239999",
        "pairing_secret": "11223344556677889900112233445566"
    }, headers={"Authorization": f"Bearer {p_token}"})
    target_id = pair_resp.json()["id"]
    code = pair_resp.json()["confirmation_code"]

    confirm_resp = client.post(f"/api/v1/devices/{target_id}/confirm-pairing", json={"confirmation_code": code}, headers={"Authorization": f"Bearer {token_2fa}"})
    assert confirm_resp.status_code == 200, confirm_resp.text

    now_epoch = int(time.time())
    canonical_bytes = create_canonical_command_bytes(
        pairing_id=pair_resp.json()["pairing_id"],
        command_id="",
        target_device_id=target_id,
        command_type="CLEAR_DEVICE_OWNER",
        sequence_counter=1,
        expiry_epoch=now_epoch + 300,
        parameters_json="{}"
    )
    sig = base64.b64encode(admin_priv.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

    dispatch_resp = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": trusted_id,
        "target_device_id": target_id,
        "command_type": "CLEAR_DEVICE_OWNER",
        "sequence_counter": 1,
        "expiry_epoch": now_epoch + 300,
        "parameters_json": "{}",
        "admin_signature": sig
    }, headers={"Authorization": f"Bearer {token_2fa}"})

    assert dispatch_resp.status_code == 201, dispatch_resp.text
    assert dispatch_resp.json()["command_type"] == "CLEAR_DEVICE_OWNER"
    assert dispatch_resp.json()["status"] == "PENDING"


def test_unenrolled_user_cannot_access_privileged_endpoints(client):
    """
    CRITICAL SECURITY INVARIANT:
    An owner account that has ONLY completed password authentication and has NOT enrolled
    in mandatory Two-Factor Authentication (TOTP) is strictly prohibited from:
    1. Dispatching recovery commands (/api/v1/devices/commands/dispatch)
    2. Generating device pairing tokens (/auth/pairing-token)
    3. Registering trusted admin devices (/auth/trusted-devices or /api/v1/devices/trusted)
    4. Accessing zero-knowledge vault items (/api/v1/vault/items)
    """
    auth_limiter.history.clear()
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register a brand-new owner account without TOTP
    username = f"unenrolled_{int(time.time())}"
    reg_resp = client.post("/auth/register", json={
        "username": username,
        "email": f"{username}@example.com",
        "password": "PasswordUnenrolled123!"
    })
    assert reg_resp.status_code == 201

    # 2. Login via password (baseline 1FA)
    login_resp = client.post("/auth/login/json", json={
        "username": username,
        "password": "PasswordUnenrolled123!"
    })
    assert login_resp.status_code == 200
    token_data = login_resp.json()
    assert token_data["is_2fa"] is False  # Password login is strictly 1FA!
    token_1fa = token_data["access_token"]
    headers = {"Authorization": f"Bearer {token_1fa}"}

    # 3. Attempt to generate device pairing token -> 403 Forbidden
    pt_resp = client.post("/auth/pairing-token", json={}, headers=headers)
    assert pt_resp.status_code == 403
    assert "two-factor authentication" in pt_resp.json()["detail"].lower()

    # 4. Attempt to register a trusted admin device -> 403 Forbidden
    admin_priv = ec.generate_private_key(ec.SECP256R1())
    admin_pub = admin_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    trusted_resp = client.post("/api/v1/devices/trusted", json={
        "device_name": "RogueAdminPhone",
        "public_key_pem": admin_pub
    }, headers=headers)
    assert trusted_resp.status_code == 403
    assert "two-factor authentication" in trusted_resp.json()["detail"].lower()

    # 5. Attempt to dispatch a recovery command -> 403 Forbidden
    dispatch_resp = client.post("/api/v1/devices/commands/dispatch", json={
        "trusted_device_id": "fake-trusted-id",
        "target_device_id": "fake-target-id",
        "command_type": "LOCK",
        "sequence_counter": 1,
        "expiry_epoch": int(time.time()) + 300,
        "parameters_json": "{}",
        "admin_signature": "ZmFrZS1zaWduYXR1cmU="
    }, headers=headers)
    assert dispatch_resp.status_code == 403
    assert "two-factor authentication" in dispatch_resp.json()["detail"].lower()

    # 6. Attempt to access vault items -> 403 Forbidden
    vault_resp = client.get("/api/v1/vault/items", headers=headers)
    assert vault_resp.status_code == 403
    assert "two-factor authentication" in vault_resp.json()["detail"].lower()

    # 7. Attempt to create a vault item -> 403 Forbidden
    vault_create = client.post("/api/v1/vault/items", json={
        "ciphertext": "AAAA",
        "iv": "BBBBBBBBBBBB",
        "tag": "CCCCCCCCCCCCCCCC",
        "salt": "DDDDDDDDDDDDDDDD",
        "key_version": 1,
        "item_type": "PASSWORD",
        "title": "Secret"
    }, headers=headers)
    assert vault_create.status_code == 403
    assert "two-factor authentication" in vault_create.json()["detail"].lower()


def test_totp_recovery_codes_and_tamper_prevention(client):
    """
    SECURITY REQUIREMENTS:
    1. Initial TOTP verification issues 8 single-use Crockford recovery codes (XXXX-XXXX).
    2. Once TOTP is enrolled, calling /auth/totp/setup without 2FA returns 403 Forbidden.
    3. Password-only (1FA) authentication cannot reset, disable, or replace TOTP.
    4. Emergency recovery code can be used in /auth/totp/validate to upgrade a 1FA session to 2FA.
    5. A recovery code is strictly single-use: subsequent attempts with the same code return 401 Unauthorized.
    6. Disabling TOTP requires an active 2FA session (/auth/totp/disable).
    """
    settings.ALLOW_PUBLIC_REGISTRATION = True
    auth_limiter.history.clear()
    totp_limiter.history.clear()

    # 1. Register owner
    username = f"recovery_user_{int(time.time())}"
    reg_resp = client.post("/auth/register", json={
        "username": username,
        "email": f"{username}@example.com",
        "password": "PasswordRecovery123!"
    })
    assert reg_resp.status_code == 201

    # 2. Login (1FA)
    login_resp = client.post("/auth/login/json", json={
        "username": username,
        "password": "PasswordRecovery123!"
    })
    assert login_resp.status_code == 200
    token_1fa = login_resp.json()["access_token"]
    assert login_resp.json()["is_2fa"] is False

    # 3. Setup TOTP
    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {token_1fa}"})
    assert setup_resp.status_code == 200
    totp_secret = setup_resp.json()["secret"]

    # 4. Generate valid TOTP code and verify
    import struct, hmac, hashlib
    key = base64.b32decode(totp_secret + "=" * ((8 - len(totp_secret) % 8) % 8), casefold=True)
    counter = int(time.time() // 30)
    msg = struct.pack(">Q", counter)
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
    valid_totp = f"{code_int % 1000000:06d}"

    verify_resp = client.post("/auth/totp/verify", json={"code": valid_totp}, headers={"Authorization": f"Bearer {token_1fa}"})
    assert verify_resp.status_code == 200
    data = verify_resp.json()
    assert data["is_2fa"] is True
    recovery_codes = data.get("recovery_codes")
    assert recovery_codes is not None
    assert len(recovery_codes) == 8
    for code in recovery_codes:
        assert len(code) == 9
        assert code[4] == "-"

    # 5. Tamper vector check: 1FA cannot call /auth/totp/setup to overwrite secret
    tamper_setup = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {token_1fa}"})
    assert tamper_setup.status_code == 403
    assert "already enabled" in tamper_setup.json()["detail"].lower()

    # 6. Tamper vector check: 1FA cannot disable TOTP
    tamper_disable = client.post("/auth/totp/disable", headers={"Authorization": f"Bearer {token_1fa}"})
    assert tamper_disable.status_code == 403

    # 7. Login again to get a fresh 1FA token
    login_again = client.post("/auth/login/json", json={
        "username": username,
        "password": "PasswordRecovery123!"
    })
    token_1fa_fresh = login_again.json()["access_token"]
    assert login_again.json()["is_2fa"] is False

    # 8. Use one recovery code to validate and upgrade session to 2FA
    test_code = recovery_codes[0]
    recov_upgrade = client.post("/auth/totp/validate", json={"code": test_code}, headers={"Authorization": f"Bearer {token_1fa_fresh}"})
    assert recov_upgrade.status_code == 200
    assert recov_upgrade.json()["is_2fa"] is True
    token_2fa_upgraded = recov_upgrade.json()["access_token"]

    # 9. Verify that the used recovery code is consumed (single-use)
    # Attempting to use test_code again must fail!
    login_third = client.post("/auth/login/json", json={
        "username": username,
        "password": "PasswordRecovery123!"
    })
    token_1fa_third = login_third.json()["access_token"]
    reused_recov = client.post("/auth/totp/validate", json={"code": test_code}, headers={"Authorization": f"Bearer {token_1fa_third}"})
    assert reused_recov.status_code == 401
    assert "invalid totp" in reused_recov.json()["detail"].lower()

    # 10. Disable TOTP using the 2FA token
    disable_resp = client.post("/auth/totp/disable", headers={"Authorization": f"Bearer {token_2fa_upgraded}"})
    assert disable_resp.status_code == 200
    assert disable_resp.json()["totp_enabled"] is False

    # 11. Now /auth/totp/setup can be called again by 1FA user
    new_setup = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {token_1fa_third}"})
    assert new_setup.status_code == 200


def test_backend_command_allowlist_and_lifecycle_commands(client):
    """
    CRITICAL SECURITY & LIFECYCLE TESTS:
    1. ALLOWED_COMMAND_TYPES = {"LOCATE", "RING", "STOP_RING", "LOCK", "STOLEN_MODE", "EXIT_KIOSK", "RECOVERED", "UNPAIR", "CLEAR_DEVICE_OWNER"}
    2. Any unauthorized/unexpected command_type (e.g. FACTORY_RESET, WIPE_ROOT, ARBITRARY_SHELL) must be rejected with 400 Bad Request.
    3. End-to-end lifecycle verification for UNPAIR, EXIT_KIOSK, RECOVERED, and CLEAR_DEVICE_OWNER:
       - Admin device signs canonical payload
       - Dispatched via /api/v1/devices/commands/dispatch (201 Created, status PENDING)
       - Guardian device polls /api/v1/devices/{device_id}/commands/poll (receives command)
       - Guardian device acknowledges /api/v1/devices/{device_id}/commands/{command_id}/ack (status EXECUTED)
    """
    settings.ALLOW_PUBLIC_REGISTRATION = True
    auth_limiter.history.clear()
    totp_limiter.history.clear()

    # 1. Setup user with 2FA
    username = f"allowlist_user_{int(time.time())}"
    reg_resp = client.post("/auth/register", json={
        "username": username,
        "email": f"{username}@example.com",
        "password": "PasswordAllowlist123!"
    })
    assert reg_resp.status_code == 201

    login_resp = client.post("/auth/login/json", json={
        "username": username,
        "password": "PasswordAllowlist123!"
    })
    token_1fa = login_resp.json()["access_token"]
    from tests.conftest import enroll_totp_and_get_2fa_token, pair_device_helper
    token_2fa = enroll_totp_and_get_2fa_token(client, token_1fa)

    # 2. Register Admin ECDSA keypair
    admin_priv = ec.generate_private_key(ec.SECP256R1())
    admin_pub = admin_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")
    trusted_resp = client.post("/api/v1/devices/trusted", json={
        "device_name": "AdminPhoneAllowlist",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    assert trusted_resp.status_code == 201
    trusted_device_id = trusted_resp.json()["id"]

    # 3. Pair Guardian device
    guardian_priv = ec.generate_private_key(ec.SECP256R1())
    guardian_pub = guardian_priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    from tests.conftest import pair_device_helper
    target_device_id, pairing_id = pair_device_helper(
        client,
        token_2fa,
        "GuardianPhoneAllowlist",
        guardian_pub,
        "+15551234567",
        "A1B2C3D4E5F67890"
    )

    seq = 1

    # 4. Test Disallowed / Arbitrary Commands Rejected
    for disallowed_type in ["FACTORY_RESET", "WIPE_ROOT", "ARBITRARY_SHELL", "UNKNOWN_CMD"]:
        seq += 1
        canonical = create_canonical_command_bytes(
            pairing_id=pairing_id,
            command_id=f"cmd-disallow-{disallowed_type}",
            target_device_id=target_device_id,
            command_type=disallowed_type,
            sequence_counter=seq,
            expiry_epoch=int(time.time()) + 300,
            parameters_json="{}"
        )
        sig = base64.b64encode(admin_priv.sign(canonical, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

        rej_resp = client.post("/api/v1/devices/commands/dispatch", json={
            "trusted_device_id": trusted_device_id,
            "target_device_id": target_device_id,
            "command_id": f"cmd-disallow-{disallowed_type}",
            "command_type": disallowed_type,
            "sequence_counter": seq,
            "expiry_epoch": int(time.time()) + 300,
            "parameters_json": "{}",
            "admin_signature": sig
        }, headers={"Authorization": f"Bearer {token_2fa}"})

        assert rej_resp.status_code in (400, 422), f"Expected 400 or 422 for {disallowed_type}, got {rej_resp.status_code}"

    # 5. Test all lifecycle commands: UNPAIR, EXIT_KIOSK, RECOVERED, CLEAR_DEVICE_OWNER
    lifecycle_types = ["UNPAIR", "EXIT_KIOSK", "RECOVERED", "CLEAR_DEVICE_OWNER"]
    for cmd_type in lifecycle_types:
        seq += 1
        cmd_id = f"cmd-lifecycle-{cmd_type}-{seq}"
        canonical = create_canonical_command_bytes(
            pairing_id=pairing_id,
            command_id=cmd_id,
            target_device_id=target_device_id,
            command_type=cmd_type,
            sequence_counter=seq,
            expiry_epoch=int(time.time()) + 300,
            parameters_json="{}"
        )
        sig = base64.b64encode(admin_priv.sign(canonical, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

        dispatch_resp = client.post("/api/v1/devices/commands/dispatch", json={
            "trusted_device_id": trusted_device_id,
            "target_device_id": target_device_id,
            "command_id": cmd_id,
            "command_type": cmd_type,
            "sequence_counter": seq,
            "expiry_epoch": int(time.time()) + 300,
            "parameters_json": "{}",
            "admin_signature": sig
        }, headers={"Authorization": f"Bearer {token_2fa}"})

        assert dispatch_resp.status_code == 201, f"Failed dispatching {cmd_type}: {dispatch_resp.text}"
        assert dispatch_resp.json()["command_type"] == cmd_type
        assert dispatch_resp.json()["status"] == "PENDING"

        # Guardian polls for command with ECDSA signature
        poll_ts = int(time.time())
        poll_bytes = f"POLL_COMMANDS|{target_device_id}|{poll_ts}".encode("utf-8")
        poll_sig = base64.b64encode(guardian_priv.sign(poll_bytes, ec.ECDSA(hashes.SHA256()))).decode("utf-8")

        poll_resp = client.get(
            f"/api/v1/devices/{target_device_id}/commands/pending",
            headers={
                "X-Device-Signature": poll_sig,
                "X-Device-Timestamp": str(poll_ts)
            }
        )
        assert poll_resp.status_code == 200, f"Failed poll for {cmd_type}: {poll_resp.text}"
        pending_cmds = poll_resp.json()
        assert len(pending_cmds) >= 1
        matched = [c for c in pending_cmds if c["id"] == cmd_id]
        assert len(matched) == 1
        assert matched[0]["command_type"] == cmd_type

        # Guardian acknowledges execution
        ack_resp = client.post(
            f"/api/v1/devices/{target_device_id}/commands/{cmd_id}/ack",
            json={"status": "EXECUTED", "reason": f"{cmd_type}_SUCCESS"}
        )
        assert ack_resp.status_code == 200, f"Failed ack for {cmd_type}: {ack_resp.text}"
        assert ack_resp.json()["command_status"] == "EXECUTED"


def test_closed_public_registration_enforcement(client, db_session):
    """
    Verifies that public registration is locked down when ALLOW_PUBLIC_REGISTRATION is disabled
    and an owner account already exists in the database.
    """
    from app.core.config import settings
    original_setting = settings.ALLOW_PUBLIC_REGISTRATION
    try:
        # Create initial owner account
        owner_resp = client.post("/auth/register", json={
            "username": "bootstrap_owner",
            "email": "bootstrap_owner@example.com",
            "password": "SecurePassword123!"
        })
        assert owner_resp.status_code in (201, 400) # Created or already exists

        # Disable public registration
        settings.ALLOW_PUBLIC_REGISTRATION = False

        # Attempt to register secondary user from public internet
        intruder_resp = client.post("/auth/register", json={
            "username": "unauthorized_user",
            "email": "unauthorized@example.com",
            "password": "Password123!"
        })
        assert intruder_resp.status_code == 403
        assert "Public registration is disabled" in intruder_resp.json()["detail"]
    finally:
        settings.ALLOW_PUBLIC_REGISTRATION = original_setting


def test_argon2id_recovery_code_one_time_consumption(client, db_session):
    """
    Verifies that Argon2id emergency recovery codes are strictly single-use.
    """
    import json
    # Register user and enroll TOTP
    uname = f"argon_user_{int(time.time())}"
    reg_resp = client.post("/auth/register", json={
        "username": uname,
        "email": f"{uname}@example.com",
        "password": "SecurePassword123!"
    })
    assert reg_resp.status_code == 201

    login_resp = client.post("/auth/login/json", json={
        "username": uname,
        "password": "SecurePassword123!"
    })
    token = login_resp.json()["access_token"]

    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {token}"})
    secret = setup_resp.json()["secret"]

    import struct, hmac, hashlib
    key = base64.b32decode(secret + "=" * ((8 - len(secret) % 8) % 8), casefold=True)
    msg = struct.pack(">Q", int(time.time() // 30))
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
    valid_code = f"{code_int % 1000000:06d}"

    verify_resp = client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {token}"})
    assert verify_resp.status_code == 200
    recovery_codes = verify_resp.json()["recovery_codes"]
    assert len(recovery_codes) == 8
    first_code = recovery_codes[0]

    # Validate first use with recovery code -> HTTP 200
    val_resp1 = client.post("/auth/totp/validate", json={"code": first_code}, headers={"Authorization": f"Bearer {token}"})
    assert val_resp1.status_code == 200
    assert val_resp1.json()["is_2fa"] is True

    # Validate second use with SAME code -> HTTP 401 Unauthorized (single use enforced!)
    val_resp2 = client.post("/auth/totp/validate", json={"code": first_code}, headers={"Authorization": f"Bearer {token}"})
    assert val_resp2.status_code == 401
    assert "Invalid TOTP" in val_resp2.json()["detail"]





