#!/usr/bin/env bash
set -euo pipefail

# SHE Database Backup Utility
# Supports both PostgreSQL (production) and SQLite (local dev/single-node)

BACKUP_DIR="${BACKUP_DIR:-/var/backups/she}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
DATABASE_URL="${DATABASE_URL:-}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"

mkdir -p "${BACKUP_DIR}"

if [[ "${DATABASE_URL}" == postgresql* ]] || [[ -n "${POSTGRES_DB:-}" ]]; then
    # PostgreSQL Backup
    DB_NAME="${POSTGRES_DB:-antitheft_db}"
    DB_USER="${POSTGRES_USER:-antitheft_user}"
    DB_HOST="${POSTGRES_HOST:-localhost}"
    DB_PORT="${POSTGRES_PORT:-5432}"
    OUT_FILE="${BACKUP_DIR}/she_pg_${DB_NAME}_${TIMESTAMP}.sql.gz"

    echo "[INFO] Starting PostgreSQL backup for ${DB_NAME} at ${TIMESTAMP}..."
    PGPASSWORD="${POSTGRES_PASSWORD:-}" pg_dump -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" --clean --if-exists | gzip > "${OUT_FILE}"
    chmod 600 "${OUT_FILE}"
    echo "[SUCCESS] PostgreSQL backup completed: ${OUT_FILE} ($(du -h "${OUT_FILE}" | cut -f1))"
else
    # SQLite Backup
    DB_FILE="${SQLITE_PATH:-backend/antitheft_dev.db}"
    if [ ! -f "${DB_FILE}" ]; then
        DB_FILE="antitheft_dev.db"
    fi
    OUT_FILE="${BACKUP_DIR}/she_sqlite_${TIMESTAMP}.db.gz"

    echo "[INFO] Starting SQLite online backup for ${DB_FILE} at ${TIMESTAMP}..."
    python3 -c "
import sqlite3, gzip, sys
src = sqlite3.connect('${DB_FILE}')
with gzip.open('${OUT_FILE}', 'wb') as f:
    dst = sqlite3.connect(':memory:')
    src.backup(dst)
    for line in dst.iterdump():
        f.write(f'{line}\n'.encode('utf-8'))
"
    chmod 600 "${OUT_FILE}"
    echo "[SUCCESS] SQLite backup completed: ${OUT_FILE} ($(du -h "${OUT_FILE}" | cut -f1))"
fi

# Rotate backups older than retention window
echo "[INFO] Rotating backups older than ${RETENTION_DAYS} days..."
find "${BACKUP_DIR}" -type f -name "she_*" -mtime "+${RETENTION_DAYS}" -delete
echo "[SUCCESS] Backup rotation complete."
