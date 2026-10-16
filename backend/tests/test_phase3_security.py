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


def test_password_only_token_cannot_dispatch_command(client):
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

    client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {init_token}"})

    # 2. Login again -> returns password-only 1FA token (is_2fa=False)
    pwd_login = client.post("/auth/login/json", json={
        "username": "pwd_only_user",
        "password": "SecurePassword123!"
    })
    pwd_token = pwd_login.json()["access_token"]
    assert pwd_login.json()["is_2fa"] is False

    # 3. Pair device and register trusted device using 1FA token (device pairing allows 1FA)
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

    pair_resp = client.post("/api/v1/devices/pair", json={
        "device_alias": "ProtectedPhone",
        "public_key_pem": guardian_pub,
        "trusted_phone_number": "+15550001111",
        "pairing_secret": "A1B2C3D4E5F60123A1B2C3D4E5F60123"
    }, headers={"Authorization": f"Bearer {pwd_token}"})
    assert pair_resp.status_code == 201
    target_id = pair_resp.json()["id"]

    admin_resp = client.post("/api/v1/devices/trusted", json={
        "device_name": "Admin Pixel",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {pwd_token}"})
    assert admin_resp.status_code == 201
    trusted_id = admin_resp.json()["id"]

    # 4. Sign a LOCK command
    now_epoch = int(time.time())
    canonical_bytes = create_canonical_command_bytes(
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



def test_guardian_signed_polling_and_rejection_ack(client, db_session):
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

    pair_resp = client.post("/api/v1/devices/pair", json={
        "device_alias": "PollTarget",
        "public_key_pem": guardian_pub,
        "trusted_phone_number": "+15558889999",
        "pairing_secret": "11223344556677881122334455667788"
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    target_id = pair_resp.json()["id"]

    admin_resp = client.post("/api/v1/devices/trusted", json={
        "device_name": "AdminController",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    trusted_id = admin_resp.json()["id"]

    # 3. Admin dispatches command with specific command_id
    now_epoch = int(time.time())
    custom_cmd_id = "test-cmd-uuid-12345"
    canonical_bytes = create_canonical_command_bytes(
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


def test_server_side_command_auto_expiry(client, db_session):
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

    target_id = client.post("/api/v1/devices/pair", json={
        "device_alias": "ExpTarget",
        "public_key_pem": guardian_pub,
        "trusted_phone_number": "+15557776666",
        "pairing_secret": "33445566778899003344556677889900"
    }, headers={"Authorization": f"Bearer {token}"}).json()["id"]

    trusted_id = client.post("/api/v1/devices/trusted", json={
        "device_name": "AdminExp",
        "public_key_pem": admin_pub
    }, headers={"Authorization": f"Bearer {token}"}).json()["id"]

    # 3. Create command with expiry in the PAST -> MUST BE REJECTED WITH 400
    past_epoch = int(time.time()) - 60
    cmd_bytes_past = create_canonical_command_bytes("", target_id, "RING", 1, past_epoch, "{}")
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
    cmd_bytes_soon = create_canonical_command_bytes("", target_id, "RING", 2, soon_epoch, "{}")
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


