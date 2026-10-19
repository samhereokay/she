#!/bin/sh
set -e

# Run database migrations automatically on container boot
alembic upgrade head || echo "WARNING: Alembic migration step failed or already up to date"

# Resolve port: Railway sets $PORT, fallback to 8000 for local/fly
PORT="${PORT:-8000}"
exec uvicorn app.main:app --host 0.0.0.0 --port "$PORT" --workers 1

