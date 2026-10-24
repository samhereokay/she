#!/bin/sh
set -e

# Run database migrations asynchronously in background so port 10000 opens in <200ms
(timeout 15 alembic upgrade head || true) &

# Resolve port: Render defaults to 10000, fallback to 8000 for local/fly
PORT="${PORT:-10000}"
exec uvicorn app.main:app --host 0.0.0.0 --port "$PORT" --workers 1

