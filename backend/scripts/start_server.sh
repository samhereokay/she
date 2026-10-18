#!/usr/bin/env bash
# =============================================================================
# SHE Anti-Theft: Background Server & Tunnel Launcher
# =============================================================================
# Launches Uvicorn listening on 0.0.0.0:8000 and optionally Cloudflare Tunnel.
# Saves PIDs to allow graceful termination via stop_server.sh.
# =============================================================================
set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
PID_DIR="$BACKEND_DIR/.run"
mkdir -p "$PID_DIR"

SERVER_PID_FILE="$PID_DIR/uvicorn.pid"
TUNNEL_PID_FILE="$PID_DIR/tunnel.pid"

# Activate virtualenv if present
if [ -d "$REPO_ROOT/.venv" ]; then
    source "$REPO_ROOT/.venv/bin/activate"
fi

# Check if already running
if [ -f "$SERVER_PID_FILE" ] && kill -0 "$(cat "$SERVER_PID_FILE")" 2>/dev/null; then
    echo "⚠️  SHE backend server is already running (PID: $(cat "$SERVER_PID_FILE"))."
else
    echo "🚀 Starting SHE backend on http://0.0.0.0:8000..."
    nohup python3 -m uvicorn app.main:app --app-dir "$BACKEND_DIR" --host 0.0.0.0 --port 8000 > "$BACKEND_DIR/server.log" 2>&1 &
    echo $! > "$SERVER_PID_FILE"
    echo "✓ Backend server running with PID: $(cat "$SERVER_PID_FILE") (logs: $BACKEND_DIR/server.log)"
fi

# Start Cloudflare Tunnel if requested or by default
START_TUNNEL="${1:-tunnel}"
if [ "$START_TUNNEL" = "tunnel" ] || [ "$START_TUNNEL" = "--tunnel" ]; then
    CLOUDFLARED="$REPO_ROOT/.tools/cloudflared"
    if [ ! -f "$CLOUDFLARED" ]; then
        echo "Downloading cloudflared binary..."
        mkdir -p "$REPO_ROOT/.tools"
        curl -s -L -o "$CLOUDFLARED" https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
        chmod +x "$CLOUDFLARED"
    fi

    if [ -f "$TUNNEL_PID_FILE" ] && kill -0 "$(cat "$TUNNEL_PID_FILE")" 2>/dev/null; then
        echo "⚠️  Cloudflare tunnel is already running (PID: $(cat "$TUNNEL_PID_FILE"))."
    else
        echo "🌐 Starting Cloudflare HTTPS Tunnel to port 8000..."
        nohup "$CLOUDFLARED" tunnel --url http://127.0.0.1:8000 > "$BACKEND_DIR/tunnel.log" 2>&1 &
        echo $! > "$TUNNEL_PID_FILE"
        echo "✓ Cloudflare tunnel running with PID: $(cat "$TUNNEL_PID_FILE") (logs: $BACKEND_DIR/tunnel.log)"
        sleep 2
        echo "-----------------------------------------------------------------"
        echo "Live domain URL from log:"
        grep -o 'https://[-a-zA-Z0-9@:%._\+~#=]\+\.trycloudflare\.com' "$BACKEND_DIR/tunnel.log" | tail -n 1 || echo "Check $BACKEND_DIR/tunnel.log for domain URL"
        echo "-----------------------------------------------------------------"
    fi
fi
