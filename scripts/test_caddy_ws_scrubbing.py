#!/usr/bin/env python3
import json
import os
import subprocess
import time
import socket
import ssl

def main():
    # 1. Start mock upstream server on 127.0.0.1:8000
    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind(("127.0.0.1", 8000))
    server_sock.listen(1)
    server_sock.settimeout(10.0)

    # 2. Adapt Caddyfile and run Caddy on ports 8080/8443
    caddy_bin = "/tmp/caddy"
    caddyfile = "/home/sam/Documents/she/backend/Caddyfile"
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
    caddy_cfg_path = "/tmp/caddy_ws_test.json"
    with open(caddy_cfg_path, "w") as f:
        json.dump(cfg, f, indent=2)

    caddy_proc = subprocess.Popen(
        [caddy_bin, "run", "--config", caddy_cfg_path],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )
    time.sleep(2)

    try:
        # 3. Send WebSocket Upgrade Request via HTTPS to Caddy on 8443
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE

        client_sock = socket.create_connection(("127.0.0.1", 8443), timeout=5.0)
        tls_client = ctx.wrap_socket(client_sock, server_hostname="api.she.local")

        req = (
            "GET /api/v1/ws/telemetry?secret_param=LEAK_QUERY_SENSITIVE&user=admin HTTP/1.1\r\n"
            "Host: api.she.local\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "Sec-WebSocket-Protocol: token.eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.SENSITIVE_WS_TOKEN\r\n"
            "Authorization: Bearer SENSITIVE_AUTH_TOKEN\r\n"
            "Cookie: session_id=SENSITIVE_COOKIE_VALUE\r\n"
            "\r\n"
        )
        tls_client.sendall(req.encode("utf-8"))

        # 4. Handle upstream response from mock
        conn, addr = server_sock.accept()
        raw_req = conn.recv(4096).decode("utf-8", errors="ignore")
        resp = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"
            "Sec-WebSocket-Protocol: token.approved\r\n"
            "Set-Cookie: auth_secret=SENSITIVE_SET_COOKIE_VALUE; Secure; HttpOnly\r\n"
            "\r\n"
        )
        conn.sendall(resp.encode("utf-8"))
        conn.close()

        # Read client response
        client_resp = tls_client.recv(4096).decode("utf-8", errors="ignore")
        tls_client.close()
        time.sleep(1)

    finally:
        caddy_proc.terminate()
        stdout_data, _ = caddy_proc.communicate(timeout=5)
        server_sock.close()

    # 5. Inspect Caddy's access log
    access_logs = []
    for line in stdout_data.splitlines():
        if "http.log.access" in line:
            access_logs.append(line)

    print("=== ACCESS LOG FOUND ===")
    for log_line in access_logs:
        print(log_line)
        # Parse JSON and verify fields
        try:
            entry = json.loads(log_line)
            req_obj = entry.get("request", {})
            headers = req_obj.get("headers", {})
            resp_headers = entry.get("resp_headers", {})
            uri = req_obj.get("uri", "")

            print("\n--- AUDIT OF FILTERED LOG FIELDS ---")
            print("URI in log:", uri)
            assert "LEAK_QUERY_SENSITIVE" not in uri, "FAILED: query string leaked in URI"
            assert uri == "/api/v1/ws/telemetry", f"FAILED: path was corrupted: {uri}"
            print("✓ URI query string stripped, path retained: /api/v1/ws/telemetry")

            assert "Sec-Websocket-Protocol" not in headers, "FAILED: Sec-Websocket-Protocol present in headers"
            assert "Authorization" not in headers, "FAILED: Authorization present in headers"
            assert "Cookie" not in headers, "FAILED: Cookie present in headers"
            print("✓ Sec-Websocket-Protocol header DELETED from log")
            print("✓ Authorization header DELETED from log")
            print("✓ Cookie header DELETED from log")

            assert "Set-Cookie" not in resp_headers, "FAILED: Set-Cookie present in resp_headers"
            print("✓ Set-Cookie response header DELETED from log")
            print("\nALL LOG SCRUBBING ASSERTIONS PASSED!")
        except Exception as e:
            print("Validation error:", e)

if __name__ == "__main__":
    main()
