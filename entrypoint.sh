#!/bin/sh
# Resolve port: Railway sets $PORT, fallback to 8000 for local/fly
PORT="${PORT:-8000}"
exec uvicorn app.main:app --host 0.0.0.0 --port "$PORT"
