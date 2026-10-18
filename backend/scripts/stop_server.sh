#!/usr/bin/env bash
# =============================================================================
# SHE Anti-Theft: Background Server & Tunnel Stopper
# =============================================================================
# Gracefully stops running Uvicorn and Cloudflare Tunnel processes.
# =============================================================================

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
PID_DIR="$BACKEND_DIR/.run"

SERVER_PID_FILE="$PID_DIR/uvicorn.pid"
TUNNEL_PID_FILE="$PID_DIR/tunnel.pid"

# Stop server by PID file
if [ -f "$SERVER_PID_FILE" ]; then
    PID="$(cat "$SERVER_PID_FILE")"
    if kill -0 "$PID" 2>/dev/null; then
        echo "Stopping SHE backend server (PID: $PID)..."
        kill "$PID" 2>/dev/null || true
        sleep 1
    fi
    rm -f "$SERVER_PID_FILE"
fi

# Stop tunnel by PID file
if [ -f "$TUNNEL_PID_FILE" ]; then
    PID="$(cat "$TUNNEL_PID_FILE")"
    if kill -0 "$PID" 2>/dev/null; then
        echo "Stopping Cloudflare tunnel (PID: $PID)..."
        kill "$PID" 2>/dev/null || true
        sleep 1
    fi
    rm -f "$TUNNEL_PID_FILE"
fi

# Catch any stray processes on port 8000
echo "Checking for remaining processes on port 8000..."
fuser -k 8000/tcp 2>/dev/null || true
pkill -f "cloudflared tunnel" 2>/dev/null || true

echo "✓ SHE services stopped cleanly."
