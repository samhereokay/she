#!/bin/sh
set -e

# Run database migrations automatically on container boot
alembic upgrade head || echo "WARNING: Alembic migration step failed or already up to date"

# Resolve port: Render defaults to 10000, fallback to 8000 for local/fly
PORT="${PORT:-10000}"
exec uvicorn app.main:app --host 0.0.0.0 --port "$PORT" --workers 1

