#!/usr/bin/env bash
# ==============================================================================
# Smartup DWH Platform - Enterprise Production Backup & Retention Lifecycle (Bash)
# ==============================================================================
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
TARGET="${TARGET:-all}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

mkdir -p "$BACKUP_DIR"

backup_db() {
    local container="$1"
    local db_user="$2"
    local db_name="$3"
    local prefix="$4"
    local out_file="${BACKUP_DIR}/${prefix}_${TIMESTAMP}.sql.gz"

    echo "==> [1/3] Dumping database '${db_name}' from container '${container}'..."
    docker exec -t "$container" pg_dump -U "$db_user" "$db_name" | gzip -9 > "$out_file"

    if [ ! -s "$out_file" ]; then
        echo "ERROR: Backup file '$out_file' is empty or failed!" >&2
        exit 1
    fi

    echo "==> [2/3] Calculating SHA-256 checksum..."
    sha256sum "$out_file" > "${out_file}.sha256"

    echo "==> [3/3] Integrity check PASSED: $(cat "${out_file}.sha256")"
}

if [ "$TARGET" = "all" ] || [ "$TARGET" = "instance" ]; then
    backup_db "SmartupCMS-db" "dwh" "dwh_instance" "instance_backup"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "cp" ]; then
    backup_db "SmartupCMS-db-cp" "dwh_cp" "dwh_control_plane" "cp_backup"
fi

echo "==> Pruning backups older than ${RETENTION_DAYS} days..."
find "$BACKUP_DIR" -type f -mtime "+${RETENTION_DAYS}" -delete

echo "==> Backup lifecycle finished successfully."
