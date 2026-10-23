web: (timeout 10 alembic upgrade head || true); exec uvicorn app.main:app --app-dir backend --host 0.0.0.0 --port ${PORT:-10000}
