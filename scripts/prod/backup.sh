#!/usr/bin/env bash
# ==============================================================================
# Smartup DWH / CMS Enterprise Automated Backup Script
# ==============================================================================
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
RETENTION_DAYS=${RETENTION_DAYS:-30}

mkdir -p "$BACKUP_DIR"

echo "Starting database backup at $TIMESTAMP..."
BACKUP_FILE="$BACKUP_DIR/dwh_instance_$TIMESTAMP.sql.gz"

docker compose exec -T db pg_dump -U dwh dwh_instance | gzip > "$BACKUP_FILE"

# SHA256 Checksum
sha256sum "$BACKUP_FILE" > "$BACKUP_FILE.sha256"
echo "Backup saved to $BACKUP_FILE with SHA256 verification."

# Retention cleanup
find "$BACKUP_DIR" -type f -name "*.sql.gz" -mtime "+$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -type f -name "*.sha256" -mtime "+$RETENTION_DAYS" -delete
echo "Retention policy applied (cleaned up backups older than $RETENTION_DAYS days)."
