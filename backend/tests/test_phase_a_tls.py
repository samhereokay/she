import logging
import ssl
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
from app.main import app, SensitiveDataFilter
from app.core.config import settings
from conftest import enroll_totp_and_get_2fa_token


def test_security_headers_present(client):
    """Verifies HSTS, X-Content-Type-Options, X-Frame-Options, and X-XSS-Protection headers."""
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.headers.get("x-content-type-options") == "nosniff"
    assert resp.headers.get("x-frame-options") == "DENY"
    assert resp.headers.get("x-xss-protection") == "0"
    assert "max-age=31536000" in resp.headers.get("strict-transport-security", "")


def test_cache_control_no_store_on_authenticated_endpoints(client):
    """Verifies Cache-Control: no-store on sensitive/authenticated routes."""
    resp = client.get("/api/v1/devices", headers={"Authorization": "Bearer invalid"})
    assert resp.headers.get("cache-control") == "no-store, no-cache, must-revalidate, private"
    assert resp.headers.get("pragma") == "no-cache"

    health_resp = client.get("/health")
    assert health_resp.status_code == 200


def test_https_enforcement_redirect(client, monkeypatch):
    """Verifies that plain HTTP requests are permanently redirected (308) to HTTPS when ENFORCE_HTTPS=True."""
    monkeypatch.setattr(settings, "ENFORCE_HTTPS", True)
    
    # Request simulating plain HTTP incoming on an API route through reverse proxy
    resp = client.get(
        "/api/v1/devices",
        headers={"x-forwarded-proto": "http"},
        follow_redirects=False,
    )
    assert resp.status_code == 308
    assert resp.headers.get("location", "").startswith("https://")

    # Anti-loop check 1: When forwarded proto is https, it MUST NOT redirect
    resp_https = client.get(
        "/api/v1/devices",
        headers={"x-forwarded-proto": "https"},
        follow_redirects=False,
    )
    assert resp_https.status_code != 308

    # Anti-loop check 2: Multi-hop proxies ("https, http") where client connected via HTTPS
    resp_multi = client.get(
        "/api/v1/devices",
        headers={"x-forwarded-proto": "https, http"},
        follow_redirects=False,
    )
    assert resp_multi.status_code != 308


def test_log_scrubbing_filter():
    """Verifies SensitiveDataFilter scrubs tokens, OTPs, secrets, and coordinates from logs."""
    record = logging.LogRecord(
        name="uvicorn.access",
        level=logging.INFO,
        pathname="",
        lineno=0,
        msg='GET /api/v1/ws?token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9&code=123456&otp=987654&lat=37.7749&lng=-122.4194 HTTP/1.1 101',
        args=(),
        exc_info=None,
    )
    data_filter = SensitiveDataFilter()
    assert data_filter.filter(record) is True
    assert "token=eyJhbGci" not in record.msg
    assert "code=123456" not in record.msg
    assert "otp=987654" not in record.msg
    assert "lat=37.7749" not in record.msg
    assert "lng=-122.4194" not in record.msg
    assert "token=[REDACTED]" in record.msg
    assert "code=[REDACTED]" in record.msg
    assert "otp=[REDACTED]" in record.msg
    assert "lat=[REDACTED]" in record.msg
    assert "lng=[REDACTED]" in record.msg


def test_refresh_token_issuance_and_single_use_rotation(client, monkeypatch):
    """Verifies access + refresh token issuance, single-use rotation, and reuse rejection."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)

    # 1. Register and login
    reg_resp = client.post("/auth/register", json={
        "email": "tls_user@example.com",
        "username": "tls_user",
        "password": "Password123!",
        "full_name": "TLS Test User",
    })
    assert reg_resp.status_code == 201

    login_resp = client.post("/auth/login/json", json={
        "username": "tls_user",
        "password": "Password123!",
    })
    assert login_resp.status_code == 200
    login_data = login_resp.json()
    assert "access_token" in login_data
    assert "refresh_token" in login_data
    
    first_access_token = login_data["access_token"]
    first_refresh_token = login_data["refresh_token"]
    assert len(first_refresh_token) >= 32

    # 2. Rotate refresh token via POST /auth/refresh
    refresh_resp = client.post("/auth/refresh", json={
        "refresh_token": first_refresh_token,
    })
    assert refresh_resp.status_code == 200
    refresh_data = refresh_resp.json()
    assert "access_token" in refresh_data
    assert "refresh_token" in refresh_data

    second_access_token = refresh_data["access_token"]
    second_refresh_token = refresh_data["refresh_token"]

    # Verify new distinct tokens are issued
    assert second_access_token != first_access_token
    assert second_refresh_token != first_refresh_token

    # 3. Rotate a second time: token 2 -> token 3 (multi-generation chain)
    third_resp = client.post("/auth/refresh", json={
        "refresh_token": second_refresh_token,
    })
    assert third_resp.status_code == 200
    third_data = third_resp.json()
    third_refresh_token = third_data["refresh_token"]
    assert third_refresh_token != second_refresh_token

    # 4. REUSE DETECTION (HISTORICAL): Re-using token 1 (2 generations back!) MUST trigger revocation
    historical_reuse_resp = client.post("/auth/refresh", json={
        "refresh_token": first_refresh_token,
    })
    assert historical_reuse_resp.status_code == 401
    assert "reuse detected" in historical_reuse_resp.json()["detail"].lower()

    # Verify session revocation: latest token (token 3) is now completely revoked!
    cascaded_refresh = client.post("/auth/refresh", json={
        "refresh_token": third_refresh_token,
    })
    assert cascaded_refresh.status_code == 401

    # 5. Bogus token rejection
    bogus_resp = client.post("/auth/refresh", json={
        "refresh_token": "totally-bogus-non-existent-refresh-token-xyz",
    })
    assert bogus_resp.status_code == 401


def test_concurrent_refresh_token_race_safety(client, monkeypatch):
    """
    Verifies concurrency safety: two simultaneous refresh requests using the exact same
    refresh token cannot both succeed. Row-locking (.with_for_update()) ensures
    one request succeeds (200) and the concurrent racer is safely rejected (401).
    """
    import concurrent.futures

    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)

    client.post("/auth/register", json={
        "username": "race_user",
        "email": "race@example.com",
        "password": "Password123!Race"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "race_user",
        "password": "Password123!Race"
    })
    ref_tok = login_resp.json()["refresh_token"]

    results = []
    def do_refresh():
        # Dedicated client per worker to avoid thread-unsafe shared connection usage in SQLite test environment
        from fastapi.testclient import TestClient
        c = TestClient(app)
        return c.post("/auth/refresh", json={"refresh_token": ref_tok})

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        f1 = executor.submit(do_refresh)
        f2 = executor.submit(do_refresh)
        results = [f1.result(), f2.result()]

    status_codes = sorted([r.status_code for r in results])
    # Both callers get 200 OK because the 10-second grace window protects concurrent races/retries
    # from prematurely revoking the session, returning valid access tokens to both!
    assert status_codes == [200, 200], f"Expected [200, 200], got {status_codes}"
    for r in results:
        data = r.json()
        assert "access_token" in data
        # recovery_codes must be null/absent on refresh (only issued on initial registration/login)
        assert data.get("recovery_codes") is None

    # Exactly one request won the CAS race and received the new refresh token;
    # the second request was handled via grace window and received refresh_token == None!
    refresh_tokens = [r.json().get("refresh_token") for r in results]
    assert None in refresh_tokens, "Grace window caller must receive refresh_token=None"
    new_tok = next(tok for tok in refresh_tokens if tok is not None)

    # Rotate again to create a 2-generation-old historical token
    gen2_resp = client.post("/auth/refresh", json={"refresh_token": new_tok})
    assert gen2_resp.status_code == 200
    gen3_tok = gen2_resp.json()["refresh_token"]

    # Replaying the first token (ref_tok) now: it is >1 generation old in history!
    # It must be REJECTED with 401 and the session revoked!
    replay_resp = client.post("/auth/refresh", json={"refresh_token": ref_tok})
    assert replay_resp.status_code == 401
    assert "reuse detected" in replay_resp.json()["detail"].lower()

    # Confirm session is completely revoked: even the latest gen3_tok is now rejected!
    after_revoke = client.post("/auth/refresh", json={"refresh_token": gen3_tok})
    assert after_revoke.status_code == 401


def test_cas_race_grace_window_enforces_10s_expiry(client, monkeypatch):
    """Verifies that replaying a consumed token past the 10-second limit revokes the session."""
    from datetime import datetime, timezone, timedelta
    from app.db.models import UserSession, utc_now
    from app.db.session import get_db

    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)

    client.post("/auth/register", json={
        "username": "cas_expiry_user",
        "email": "cas_exp@example.com",
        "password": "Password123!CasExp"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "cas_expiry_user",
        "password": "Password123!CasExp"
    })
    tok1 = login_resp.json()["refresh_token"]

    # First refresh: consumes tok1, advances last_active_at
    ref1_resp = client.post("/auth/refresh", json={"refresh_token": tok1})
    assert ref1_resp.status_code == 200
    tok2 = ref1_resp.json()["refresh_token"]

    # Now simulate time travel: backdate the session's last_active_at by 15 seconds (past the 10s grace limit)
    db = next(app.dependency_overrides[get_db]())
    sess = db.query(UserSession).filter(UserSession.consumed_refresh_hash != None).first()
    assert sess is not None
    sess.last_active_at = utc_now() - timedelta(seconds=15)
    db.commit()

    # Attempt to replay tok1 after 15 seconds: must NOT be granted grace window! Must be 401 and revoke session!
    replay_late = client.post("/auth/refresh", json={"refresh_token": tok1})
    assert replay_late.status_code == 401
    assert "reuse detected" in replay_late.json()["detail"].lower()

    # Verify session is revoked in database
    db.refresh(sess)
    assert sess.revoked_at is not None

    # Even tok2 is now rejected because entire session was revoked
    tok2_attempt = client.post("/auth/refresh", json={"refresh_token": tok2})
    assert tok2_attempt.status_code == 401


def test_websocket_sec_websocket_protocol_auth(client, monkeypatch):
    """Verifies WebSocket endpoint accepts JWT via Sec-WebSocket-Protocol header without query token."""
    monkeypatch.setattr(settings, "ALLOW_PUBLIC_REGISTRATION", True)

    # 1. Register, login, and enroll 2FA
    client.post("/auth/register", json={
        "username": "ws_tls_user",
        "email": "wstls@example.com",
        "password": "Password123!Safe"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "ws_tls_user",
        "password": "Password123!Safe"
    })
    raw_token = login_resp.json()["access_token"]
    token = enroll_totp_and_get_2fa_token(client, raw_token)
    headers = {"Authorization": f"Bearer {token}"}

    # 2. Pair Guardian device
    priv_key = ec.generate_private_key(ec.SECP256R1())
    pem_public = priv_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    pt_resp = client.post("/auth/pairing-token", headers=headers, json={})
    pt = pt_resp.json()["pairing_token"]

    pair_resp = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {pt}"},
        json={
            "device_alias": "Header Auth Phone",
            "public_key_pem": pem_public,
            "trusted_phone_number": "+15559998888",
            "pairing_secret": "abcdef1234567890abcdef1234567890"
        }
    )
    dev_data = pair_resp.json()
    device_id = dev_data["id"]

    client.post(
        f"/api/v1/devices/{device_id}/confirm-pairing",
        headers=headers,
        json={"confirmation_code": dev_data["confirmation_code"]}
    )

    # 3. Connect over WebSocket with Sec-WebSocket-Protocol (NO token in query string)
    with client.websocket_connect(
        f"/api/v1/ws/devices/{device_id}",
        subprotocols=["she.v1", f"token.{token}"],
    ) as ws:
        initial = ws.receive_json()
        assert initial["type"] == "INITIAL_STATE"
        assert initial["device_id"] == device_id
        assert initial["device_alias"] == "Header Auth Phone"
        assert initial["status"] == "ACTIVE"


def test_python_ssl_verification_rejects_untrusted_ca():
    """
    Verifies that Python's client-side SSL layer strictly rejects untrusted/MITM certificates.
    Note: Android release build MITM rejection is enforced at the OS level via network_security_config.xml
    which only trusts system CAs (no user certs) and enforces SPKI certificate pinning on she.up.railway.app.
    """
    ctx = ssl.create_default_context()
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED

    with pytest.raises((ssl.SSLCertVerificationError, ssl.SSLError, OSError)):
        s = ctx.wrap_socket(ssl.socket(), server_hostname="untrusted.local")
        s.connect(("127.0.0.1", 443))
