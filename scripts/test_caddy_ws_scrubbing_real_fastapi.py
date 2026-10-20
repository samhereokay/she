#!/usr/bin/env python3
import json
import os
import subprocess
import time
import socket
import ssl
import sys
import hashlib
import secrets
from datetime import timedelta

# Set working directory to backend
repo_dir = "/home/sam/Documents/she"
os.chdir(os.path.join(repo_dir, "backend"))
sys.path.insert(0, os.path.join(repo_dir, "backend"))

from app.core.config import settings
from app.db.session import engine, Base, SessionLocal
from app.db.models import User, ProtectedDevice, UserSession, utc_now
from app.core.security import hash_password, create_access_token

def setup_test_data():
    Base.metadata.create_all(bind=engine)
    db = SessionLocal()
    
    # Clean up any existing test records
    db.query(UserSession).filter(UserSession.id == "sess-ws-scrub-101").delete()
    db.query(ProtectedDevice).filter(ProtectedDevice.id == "dev-ws-scrub-101").delete()
    db.query(User).filter(User.id == "user-ws-scrub-101").delete()
    db.commit()

    # Create test user
    user = User(
        id="user-ws-scrub-101",
        username="ws_scrub_user_101",
        email="ws_scrub_101@example.com",
        hashed_password=hash_password("Password123!Safe"),
        totp_enabled=True,
        totp_secret="TESTSECRET101",
        email_verified_at=utc_now()
    )
    db.add(user)
    
    # Create active protected device
    device = ProtectedDevice(
        id="dev-ws-scrub-101",
        user_id=user.id,
        device_alias="WS Scrub 101 Phone",
        status="ACTIVE",
        trusted_phone_number="+15550001111",
        public_key_pem="MOCK_PEM_KEY",
        pairing_secret_hash=hashlib.sha256(b"dummy_secret").hexdigest(),
        created_at=utc_now()
    )
    db.add(device)
    
    # Create session
    session_jti = secrets.token_hex(16)
    user_sess = UserSession(
        id="sess-ws-scrub-101",
        user_id=user.id,
        session_token_jti=session_jti,
        device_info="SHE-Admin-Android",
        is_registered_device=True,
        is_2fa=True,
        refresh_token_hash=hashlib.sha256(b"refresh_token_val").hexdigest(),
        created_at=utc_now(),
        last_active_at=utc_now()
    )
    db.add(user_sess)
    user_id = user.id
    device_id = device.id
    db.commit()
    db.close()
    
    # Generate valid 2FA access token signed with settings.SECRET_KEY
    access_token = create_access_token(
        subject=user_id,
        is_2fa=True,
        expires_delta=timedelta(minutes=30),
        jti=session_jti
    )
    return device_id, access_token

def cleanup_test_data():
    try:
        db = SessionLocal()
        db.query(UserSession).filter(UserSession.id == "sess-ws-scrub-101").delete()
        db.query(ProtectedDevice).filter(ProtectedDevice.id == "dev-ws-scrub-101").delete()
        db.query(User).filter(User.id == "user-ws-scrub-101").delete()
        db.commit()
        db.close()
    except Exception:
        pass

def main():
    device_id, valid_jwt = setup_test_data()
    print(f"Generated test fixture: device_id={device_id}")
    print(f"Generated valid 2FA JWT: {valid_jwt[:20]}...{valid_jwt[-10:]}")

    caddy_bin = "/tmp/caddy"
    caddyfile = os.path.join(repo_dir, "backend", "Caddyfile")

    # 1. Start the REAL FastAPI backend using uvicorn on 127.0.0.1:8000
    fastapi_env = os.environ.copy()
    fastapi_env["PYTHONPATH"] = os.path.join(repo_dir, "backend")
    fastapi_proc = subprocess.Popen(
        ["python3", "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000"],
        cwd=os.path.join(repo_dir, "backend"),
        env=fastapi_env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    # Wait for FastAPI to listen on 8000
    for _ in range(30):
        try:
            with socket.create_connection(("127.0.0.1", 8000), timeout=0.5):
                break
        except Exception:
            time.sleep(0.1)

    # 2. Adapt Caddyfile and run Caddy on ports 8080/8443
    adapt_res = subprocess.run([caddy_bin, "adapt", "--config", caddyfile], capture_output=True, text=True, check=True)
    cfg = json.loads(adapt_res.stdout)
    cfg.setdefault("apps", {}).setdefault("pki", {}).setdefault("certificate_authorities", {})["local"] = {
        "install_trust": False
    }
    for name, srv in cfg.get("apps", {}).get("http", {}).get("servers", {}).items():
        srv["listen"] = [
            ":8443" if x == ":443" else ":8080" if x == ":80" else x
            for x in srv.get("listen", [])
        ]
    caddy_cfg_path = "/tmp/caddy_ws_real_test.json"
    with open(caddy_cfg_path, "w") as f:
        json.dump(cfg, f, indent=2)

    caddy_proc = subprocess.Popen(
        [caddy_bin, "run", "--config", caddy_cfg_path],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )
    
    # Wait for Caddy to listen on 8443
    for _ in range(30):
        try:
            with socket.create_connection(("127.0.0.1", 8443), timeout=0.5):
                break
        except Exception:
            time.sleep(0.1)

    try:
        # 3. Send WebSocket Upgrade Request through Caddy to the real FastAPI with VALID JWT
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE

        client_sock = socket.create_connection(("127.0.0.1", 8443), timeout=5.0)
        tls_client = ctx.wrap_socket(client_sock, server_hostname="api.she.local")

        req = (
            f"GET /api/v1/ws/devices/{device_id}?token_leak_in_query=SECRET_QUERY_VAL&debug=1 HTTP/1.1\r\n"
            "Host: api.she.local\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            f"Sec-WebSocket-Protocol: she.v1, token.{valid_jwt}\r\n"
            "Authorization: Bearer SENSITIVE_AUTH_TOKEN_TEST\r\n"
            "Cookie: session_id=SENSITIVE_COOKIE_LEAK_TEST\r\n"
            "\r\n"
        )
        tls_client.sendall(req.encode("utf-8"))

        # Read backend response
        resp_data = tls_client.recv(4096).decode("utf-8", errors="ignore")
        status_line = resp_data.split("\r\n")[0] if resp_data else "(none)"
        print("Backend response status line:", status_line)
        print("Backend handshake headers:\n" + resp_data[:500])
        tls_client.close()
        time.sleep(1)

    finally:
        caddy_proc.terminate()
        stdout_data, _ = caddy_proc.communicate(timeout=5)
        fastapi_proc.terminate()
        fast_out, fast_err = fastapi_proc.communicate(timeout=5)
        print("=== FASTAPI STDOUT ===")
        print(fast_out)
        print("=== FASTAPI STDERR ===")
        print(fast_err)
        cleanup_test_data()

    assert "101 Switching Protocols" in status_line, f"Handshake failed! Got: {status_line}"

    # 4. Parse Caddy's access log
    access_logs = [line for line in stdout_data.splitlines() if "http.log.access" in line]
    print(f"\n=== CADDY ACCESS LOGS ({len(access_logs)} found) ===")
    assert len(access_logs) > 0, "No access logs recorded by Caddy!"
    for log_line in access_logs:
        print(log_line)
        entry = json.loads(log_line)
        status = entry.get("status")
        req_obj = entry.get("request", {})
        headers = req_obj.get("headers", {})
        resp_headers = entry.get("resp_headers", {})
        uri = req_obj.get("uri", "")

        print("\n--- VALIDATION AGAINST REAL FASTAPI (STATUS 101) ---")
        print("Logged HTTP Status:", status)
        assert status == 101, f"Expected status 101 in Caddy log, got {status}"
        print("✓ Confirmed WebSocket Upgrade returned HTTP 101 Switching Protocols")

        print("Logged URI:", uri)
        assert "SECRET_QUERY_VAL" not in uri, "FAILED: Query string leaked in URI"
        assert uri == f"/api/v1/ws/devices/{device_id}", f"FAILED: Path corrupted: {uri}"
        print(f"✓ URI query string stripped, path retained: /api/v1/ws/devices/{device_id}")

        assert "Sec-Websocket-Protocol" not in headers, "FAILED: Sec-Websocket-Protocol in request headers"
        assert "Authorization" not in headers, "FAILED: Authorization in request headers"
        assert "Cookie" not in headers, "FAILED: Cookie in request headers"
        print("✓ request>headers>Sec-Websocket-Protocol DELETED from log")
        print("✓ request>headers>Authorization DELETED from log")
        print("✓ request>headers>Cookie DELETED from log")

        assert "Sec-Websocket-Protocol" not in resp_headers, "FAILED: Sec-Websocket-Protocol in resp_headers"
        assert "Set-Cookie" not in resp_headers, "FAILED: Set-Cookie in resp_headers"
        print("✓ resp_headers>Sec-Websocket-Protocol DELETED from log")
        print("✓ resp_headers>Set-Cookie DELETED from log")
        print("\nALL REAL-FASTAPI CADDY LOG SCRUBBING ASSERTIONS (WITH STATUS 101) PASSED!")

if __name__ == "__main__":
    main()
