#!/usr/bin/env bash
set -euo pipefail

# SHE Database Restore Utility
# Restores database from a compressed .sql.gz or .db.gz backup archive

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <path_to_backup_file> [--force]"
    exit 1
fi

BACKUP_FILE="$1"
FORCE="${2:-}"

if [ ! -f "${BACKUP_FILE}" ]; then
    echo "[ERROR] Backup file not found: ${BACKUP_FILE}"
    exit 1
fi

if [ "${FORCE}" != "--force" ]; then
    echo "===================================================================="
    echo "!!! CAUTION: DATABASE RESTORE IN PROGRESS !!!"
    echo "Restoring from '${BACKUP_FILE}' will overwrite current database state."
    echo "To proceed non-interactively, pass '--force'."
    echo "===================================================================="
    read -r -p "Type 'RESTORE' to confirm: " CONFIRM
    if [ "${CONFIRM}" != "RESTORE" ]; then
        echo "[ABORTED] Restore cancelled by operator."
        exit 1
    fi
fi

if [[ "${BACKUP_FILE}" == *.sql.gz ]]; then
    DB_NAME="${POSTGRES_DB:-antitheft_db}"
    DB_USER="${POSTGRES_USER:-antitheft_user}"
    DB_HOST="${POSTGRES_HOST:-localhost}"
    DB_PORT="${POSTGRES_PORT:-5432}"

    echo "[INFO] Restoring PostgreSQL database '${DB_NAME}' from ${BACKUP_FILE}..."
    gunzip -c "${BACKUP_FILE}" | PGPASSWORD="${POSTGRES_PASSWORD:-}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}"
    echo "[SUCCESS] PostgreSQL restore completed successfully."
elif [[ "${BACKUP_FILE}" == *.db.gz ]]; then
    TARGET_DB="${SQLITE_PATH:-backend/antitheft_dev.db}"
    echo "[INFO] Restoring SQLite database '${TARGET_DB}' from ${BACKUP_FILE}..."
    python3 -c "
import sqlite3, gzip
with gzip.open('${BACKUP_FILE}', 'rt', encoding='utf-8') as f:
    sql = f.read()
con = sqlite3.connect('${TARGET_DB}')
con.executescript(sql)
con.close()
"
    echo "[SUCCESS] SQLite restore completed successfully."
else
    echo "[ERROR] Unsupported backup format. Expected .sql.gz or .db.gz"
    exit 1
fi
