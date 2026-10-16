import os
import json
import base64
import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization

from app.core.config import settings
from app.db.models import User, VaultItem, AuditEvent


def _helper_setup_user_with_2fa(client, username="vault_user", email="vault@example.com"):
    """Helper to register user and obtain 2FA-upgraded session token."""
    settings.ALLOW_PUBLIC_REGISTRATION = True
    client.post("/auth/register", json={
        "username": username,
        "email": email,
        "password": "VaultPassword123!"
    })
    init_token = client.post("/auth/login/json", json={
        "username": username,
        "password": "VaultPassword123!"
    }).json()["access_token"]

    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {init_token}"})
    secret = setup_resp.json()["secret"]

    import time, struct, hmac, hashlib
    key = base64.b32decode(secret + "=" * ((8 - len(secret) % 8) % 8), casefold=True)
    counter = int(time.time() // 30)
    msg = struct.pack(">Q", counter)
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
    valid_code = f"{code_int % 1000000:06d}"

    v_resp = client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {init_token}"})
    token_2fa = v_resp.json()["access_token"]
    return token_2fa


def test_vault_requires_2fa(client):
    """Enforces that 1FA sessions (password only) return HTTP 403 when accessing vault."""
    settings.ALLOW_PUBLIC_REGISTRATION = True
    client.post("/auth/register", json={
        "username": "pwd_vault_user",
        "email": "pvu@example.com",
        "password": "VaultPassword123!"
    })
    # Set up TOTP so subsequent login yields is_2fa=False
    init_tok = client.post("/auth/login/json", json={
        "username": "pwd_vault_user",
        "password": "VaultPassword123!"
    }).json()["access_token"]

    setup_resp = client.post("/auth/totp/setup", headers={"Authorization": f"Bearer {init_tok}"})
    secret = setup_resp.json()["secret"]

    import time, struct, hmac, hashlib
    key = base64.b32decode(secret + "=" * ((8 - len(secret) % 8) % 8), casefold=True)
    msg = struct.pack(">Q", int(time.time() // 30))
    h = hmac.new(key, msg, hashlib.sha1).digest()
    idx = h[-1] & 0x0F
    valid_code = f"{(struct.unpack('>I', h[idx:idx + 4])[0] & 0x7FFFFFFF) % 1000000:06d}"
    client.post("/auth/totp/verify", json={"code": valid_code}, headers={"Authorization": f"Bearer {init_tok}"})

    # Subsequent login gives 1FA token (is_2fa=False)
    login_resp = client.post("/auth/login/json", json={
        "username": "pwd_vault_user",
        "password": "VaultPassword123!"
    })
    token_1fa = login_resp.json()["access_token"]
    assert login_resp.json()["is_2fa"] is False

    # Attempt to access vault with 1FA -> MUST RETURN HTTP 403
    unauth_list = client.get("/api/v1/vault/items", headers={"Authorization": f"Bearer {token_1fa}"})
    assert unauth_list.status_code == 403
    assert "two-factor authentication" in unauth_list.json()["detail"].lower()

    unauth_create = client.post("/api/v1/vault/items", json={
        "item_type": "RECOVERY_CODE",
        "title": "Secret Codes",
        "encrypted_blob": base64.b64encode(b"dummy").decode(),
        "iv": base64.b64encode(os.urandom(12)).decode(),
        "tag": base64.b64encode(os.urandom(16)).decode()
    }, headers={"Authorization": f"Bearer {token_1fa}"})
    assert unauth_create.status_code == 403


def test_vault_zero_knowledge_storage_and_roundtrip(client, db_session):
    """
    Simulates client-side AES-256-GCM encryption with local master key.
    Proves that server stores only ciphertext and client successfully decrypts roundtrip.
    """
    token_2fa = _helper_setup_user_with_2fa(client, "zk_user", "zk@example.com")

    # Client generates 256-bit AES Master Key locally (stored in Android Keystore)
    client_vault_key = AESGCM.generate_key(bit_length=256)
    aesgcm = AESGCM(client_vault_key)

    plaintext_secret = json.dumps({
        "imei_1": "358291048291823",
        "imei_2": "358291048291824",
        "serial": "8A2X9910BC",
        "recovery_key": "ABCD-1234-EFGH-5678",
        "insurance_policy": "POLICY-SEC-9921"
    })
    iv = os.urandom(12)  # 96-bit standard GCM IV
    ciphertext_with_tag = aesgcm.encrypt(iv, plaintext_secret.encode("utf-8"), None)
    ciphertext = ciphertext_with_tag[:-16]
    tag = ciphertext_with_tag[-16:]

    iv_b64 = base64.b64encode(iv).decode("utf-8")
    tag_b64 = base64.b64encode(tag).decode("utf-8")
    blob_b64 = base64.b64encode(ciphertext).decode("utf-8")

    # 1. Create vault item
    create_resp = client.post("/api/v1/vault/items", json={
        "item_type": "DEVICE_IDENTIFIER",
        "title": "Pixel 8 Pro IMEI & Recovery Secrets",
        "encrypted_blob": blob_b64,
        "iv": iv_b64,
        "tag": tag_b64,
        "key_version": 1
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    assert create_resp.status_code == 201
    item_id = create_resp.json()["id"]

    # 2. Check Database: Plaintext MUST NOT exist anywhere in database row
    db_item = db_session.query(VaultItem).filter(VaultItem.id == item_id).first()
    assert db_item is not None
    assert "358291048291823" not in db_item.encrypted_blob
    assert "ABCD-1234" not in db_item.encrypted_blob
    assert "POLICY-SEC" not in db_item.encrypted_blob

    # 3. Client retrieves vault item
    get_resp = client.get(f"/api/v1/vault/items/{item_id}", headers={"Authorization": f"Bearer {token_2fa}"})
    assert get_resp.status_code == 200
    retrieved = get_resp.json()

    # 4. Client decrypts retrieved item locally
    ret_iv = base64.b64decode(retrieved["iv"])
    ret_tag = base64.b64decode(retrieved["tag"])
    ret_ct = base64.b64decode(retrieved["encrypted_blob"])
    decrypted_raw = aesgcm.decrypt(ret_iv, ret_ct + ret_tag, None)
    decrypted_json = json.loads(decrypted_raw.decode("utf-8"))

    assert decrypted_json["imei_1"] == "358291048291823"
    assert decrypted_json["recovery_key"] == "ABCD-1234-EFGH-5678"


def test_vault_invalid_crypto_encodings_rejected(client):
    """Enforces that corrupted Base64 or wrong IV/Tag byte lengths return HTTP 422."""
    token_2fa = _helper_setup_user_with_2fa(client, "bad_enc_user", "beu@example.com")

    # IV must be exactly 12 bytes. If 8 bytes provided -> 422
    bad_iv = base64.b64encode(os.urandom(8)).decode("utf-8")
    good_tag = base64.b64encode(os.urandom(16)).decode("utf-8")
    good_blob = base64.b64encode(b"dummy_ciphertext").decode("utf-8")

    resp1 = client.post("/api/v1/vault/items", json={
        "item_type": "RECOVERY_CODE",
        "title": "Bad IV",
        "encrypted_blob": good_blob,
        "iv": bad_iv,
        "tag": good_tag
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    assert resp1.status_code == 422
    assert "cryptographic encoding" in resp1.json()["detail"].lower()

    # Tag must be exactly 16 bytes. If 12 bytes provided -> 422
    bad_tag = base64.b64encode(os.urandom(12)).decode("utf-8")
    good_iv = base64.b64encode(os.urandom(12)).decode("utf-8")
    resp2 = client.post("/api/v1/vault/items", json={
        "item_type": "RECOVERY_CODE",
        "title": "Bad Tag",
        "encrypted_blob": good_blob,
        "iv": good_iv,
        "tag": bad_tag
    }, headers={"Authorization": f"Bearer {token_2fa}"})
    assert resp2.status_code == 422


def test_vault_cross_user_isolation(client):
    """Enforces that User A cannot read, update or delete User B's vault items (HTTP 404)."""
    token_a = _helper_setup_user_with_2fa(client, "user_vault_a", "uva@example.com")
    token_b = _helper_setup_user_with_2fa(client, "user_vault_b", "uvb@example.com")

    # User B creates vault item
    iv = base64.b64encode(os.urandom(12)).decode("utf-8")
    tag = base64.b64encode(os.urandom(16)).decode("utf-8")
    blob = base64.b64encode(b"user_b_secret_data").decode("utf-8")

    create_b = client.post("/api/v1/vault/items", json={
        "item_type": "RECOVERY_CODE",
        "title": "User B Secret",
        "encrypted_blob": blob,
        "iv": iv,
        "tag": tag
    }, headers={"Authorization": f"Bearer {token_b}"})
    assert create_b.status_code == 201
    item_b_id = create_b.json()["id"]

    # User A tries to GET User B's item -> 404
    cross_get = client.get(f"/api/v1/vault/items/{item_b_id}", headers={"Authorization": f"Bearer {token_a}"})
    assert cross_get.status_code == 404

    # User A tries to UPDATE User B's item -> 404
    cross_update = client.put(f"/api/v1/vault/items/{item_b_id}", json={
        "title": "Hacked Title"
    }, headers={"Authorization": f"Bearer {token_a}"})
    assert cross_update.status_code == 404

    # User A tries to DELETE User B's item -> 404
    cross_delete = client.delete(f"/api/v1/vault/items/{item_b_id}", headers={"Authorization": f"Bearer {token_a}"})
    assert cross_delete.status_code == 404

    # User A's list MUST NOT contain User B's item
    list_a = client.get("/api/v1/vault/items", headers={"Authorization": f"Bearer {token_a}"}).json()["items"]
    assert len(list_a) == 0


def test_vault_item_update_and_key_rotation(client, db_session):
    """Verifies that items can be updated with rotated key versions and audit events are logged."""
    token = _helper_setup_user_with_2fa(client, "rot_user", "rot@example.com")

    # Initial creation with key v1
    iv1 = base64.b64encode(os.urandom(12)).decode("utf-8")
    tag1 = base64.b64encode(os.urandom(16)).decode("utf-8")
    blob1 = base64.b64encode(b"old_ciphertext_v1").decode("utf-8")

    item_id = client.post("/api/v1/vault/items", json={
        "item_type": "PURCHASE_RECEIPT",
        "title": "Phone Purchase Receipt",
        "encrypted_blob": blob1,
        "iv": iv1,
        "tag": tag1,
        "key_version": 1
    }, headers={"Authorization": f"Bearer {token}"}).json()["id"]

    # Re-encrypt with rotated key v2
    iv2 = base64.b64encode(os.urandom(12)).decode("utf-8")
    tag2 = base64.b64encode(os.urandom(16)).decode("utf-8")
    blob2 = base64.b64encode(b"new_ciphertext_v2").decode("utf-8")

    update_resp = client.put(f"/api/v1/vault/items/{item_id}", json={
        "title": "Rotated Receipt (v2)",
        "encrypted_blob": blob2,
        "iv": iv2,
        "tag": tag2,
        "key_version": 2
    }, headers={"Authorization": f"Bearer {token}"})
    assert update_resp.status_code == 200
    assert update_resp.json()["key_version"] == 2
    assert update_resp.json()["title"] == "Rotated Receipt (v2)"

    # Audit event logged
    audit = db_session.query(AuditEvent).filter(
        AuditEvent.event_type == "VAULT_ITEM_UPDATED"
    ).first()
    assert audit is not None
    assert "key v2" in audit.details


def test_vault_deletion_and_purge(client, db_session):
    """Verifies single item deletion and emergency cryptographic shredding (purge entire vault)."""
    token = _helper_setup_user_with_2fa(client, "shred_user", "shred@example.com")

    # Create 3 items
    for i in range(3):
        client.post("/api/v1/vault/items", json={
            "item_type": "RECOVERY_CODE",
            "title": f"Secret {i}",
            "encrypted_blob": base64.b64encode(b"secret").decode(),
            "iv": base64.b64encode(os.urandom(12)).decode(),
            "tag": base64.b64encode(os.urandom(16)).decode()
        }, headers={"Authorization": f"Bearer {token}"})

    list_resp = client.get("/api/v1/vault/items", headers={"Authorization": f"Bearer {token}"})
    assert list_resp.json()["total"] == 3

    # Delete 1 item
    first_id = list_resp.json()["items"][0]["id"]
    del_resp = client.delete(f"/api/v1/vault/items/{first_id}", headers={"Authorization": f"Bearer {token}"})
    assert del_resp.status_code == 200
    assert del_resp.json()["status"] == "DELETED"

    rem_list = client.get("/api/v1/vault/items", headers={"Authorization": f"Bearer {token}"})
    assert rem_list.json()["total"] == 2

    # Purge entire vault
    purge_resp = client.delete("/api/v1/vault/purge", headers={"Authorization": f"Bearer {token}"})
    assert purge_resp.status_code == 200
    assert purge_resp.json()["deleted_count"] == 2

    final_list = client.get("/api/v1/vault/items", headers={"Authorization": f"Bearer {token}"})
    assert final_list.json()["total"] == 0

    # Audit event logged
    purge_audit = db_session.query(AuditEvent).filter(AuditEvent.event_type == "VAULT_PURGED").first()
    assert purge_audit is not None
    assert "2 items permanently deleted" in purge_audit.details
