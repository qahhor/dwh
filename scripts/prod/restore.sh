#!/usr/bin/env bash
# ==============================================================================
# Smartup DWH / CMS Automated Disaster Recovery Restore Script
# ==============================================================================
set -euo pipefail

if [ $# -eq 0 ]; then
    echo "Usage: $0 <path_to_backup_file.sql.gz>"
    exit 1
fi

BACKUP_FILE="$1"

if [ ! -f "$BACKUP_FILE" ]; then
    echo "[ERROR] Backup file $BACKUP_FILE does not exist!"
    exit 1
fi

echo "[1/3] Verifying Checksum..."
if [ -f "$BACKUP_FILE.sha256" ]; then
    sha256sum -c "$BACKUP_FILE.sha256"
fi

echo "[2/3] Terminating Active DB Connections & Restoring..."
docker compose exec -T db psql -U dwh -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'dwh_instance' AND pid <> pg_backend_pid();"
docker compose exec -T db psql -U dwh -d postgres -c "DROP DATABASE IF EXISTS dwh_instance;"
docker compose exec -T db psql -U dwh -d postgres -c "CREATE DATABASE dwh_instance OWNER dwh;"

gzip -dc "$BACKUP_FILE" | docker compose exec -T db psql -U dwh -d dwh_instance

echo "[3/3] Running Schema Version Gate Check..."
docker compose run --rm migrate

echo "Database restore completed successfully!"
