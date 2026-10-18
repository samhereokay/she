#!/usr/bin/env bash
# =============================================================================
# SHE Anti-Theft: Production-Ready Local Domain & Tunnel Runner
# =============================================================================
# Exposes the local FastAPI backend (127.0.0.1:8000) over a secure, public HTTPS
# domain using Cloudflare Tunnel. Enables 2 mobile devices (Admin and Guardian)
# to connect over Wi-Fi, 4G, or 5G without raw IP addresses or port forwarding.
# =============================================================================
set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLOUDFLARED="$REPO_ROOT/.tools/cloudflared"

if [ ! -f "$CLOUDFLARED" ]; then
    echo "Downloading cloudflared binary..."
    mkdir -p "$REPO_ROOT/.tools"
    curl -s -L -o "$CLOUDFLARED" https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
    chmod +x "$CLOUDFLARED"
fi

echo "================================================================="
echo "🛡️  SHE Local Domain Server (Cloudflare HTTPS Tunnel)"
echo "================================================================="
echo "Backend Target: http://127.0.0.1:8000"
echo "Starting tunnel... Copy the HTTPS URL below into Admin & Guardian:"
echo "================================================================="

exec "$CLOUDFLARED" tunnel --url http://127.0.0.1:8000
