#!/usr/bin/env python3
import subprocess
import json
import os
import sys

def main():
    caddy_bin = "/tmp/caddy"
    caddyfile_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "backend", "Caddyfile")
    
    # 1. Adapt Caddyfile to Caddy JSON
    res = subprocess.run([caddy_bin, "adapt", "--config", caddyfile_path], capture_output=True, text=True)
    if res.returncode != 0:
        print("Error adapting Caddyfile:", res.stderr, file=sys.stderr)
        sys.exit(1)
        
    cfg = json.loads(res.stdout)
    
    # 2. Disable root CA installation prompt for local non-root development
    cfg.setdefault("apps", {}).setdefault("pki", {}).setdefault("certificate_authorities", {})["local"] = {
        "install_trust": False
    }
    
    # 3. Honor HTTP_PORT and HTTPS_PORT env vars
    http_port = os.getenv("HTTP_PORT", "80")
    https_port = os.getenv("HTTPS_PORT", "443")
    for name, srv in cfg.get("apps", {}).get("http", {}).get("servers", {}).items():
        srv["listen"] = [
            f":{https_port}" if x == ":443" else f":{http_port}" if x == ":80" else x
            for x in srv.get("listen", [])
        ]
        
    out_json = "/tmp/caddy_active.json"
    with open(out_json, "w") as f:
        json.dump(cfg, f, indent=2)
        
    # 4. Exec Caddy
    os.execv(caddy_bin, [caddy_bin, "run", "--config", out_json])

if __name__ == "__main__":
    main()
