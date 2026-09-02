#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <backup.dump.age> <age-identity-file>" >&2
    exit 1
fi

BACKUP_FILE="$(realpath "$1")"
IDENTITY_FILE="$(realpath "$2")"
CHECKSUM_FILE="${BACKUP_FILE}.sha256"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
TIMESTAMP="$(date -u +'%Y%m%dT%H%M%SZ')"
compose=(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE")

[[ -f "$BACKUP_FILE" ]] || { echo "[ERROR] Backup file does not exist." >&2; exit 1; }
[[ -f "$IDENTITY_FILE" ]] || { echo "[ERROR] age identity file does not exist." >&2; exit 1; }
[[ -f "$CHECKSUM_FILE" ]] || { echo "[ERROR] Backup checksum does not exist." >&2; exit 1; }
[[ -f "$ENV_FILE" ]] || { echo "[ERROR] Environment file does not exist." >&2; exit 1; }

expected_hash="$(awk 'NR == 1 {print tolower($1)}' "$CHECKSUM_FILE")"
actual_hash="$(sha256sum "$BACKUP_FILE" | awk '{print tolower($1)}')"
[[ -n "$expected_hash" && "$expected_hash" == "$actual_hash" ]] \
    || { echo '[ERROR] Encrypted backup SHA-256 verification failed.' >&2; exit 1; }

"${compose[@]}" config --quiet
[[ -n "$("${compose[@]}" ps -a -q postgres)" ]] \
    || { echo '[ERROR] PostgreSQL is not running.' >&2; exit 1; }

archive_dir="$(dirname "$BACKUP_FILE")"
archive_name="$(basename "$BACKUP_FILE")"
decrypt=("${compose[@]}" run --rm --no-deps --entrypoint age \
    -v "${archive_dir}:/restore:ro" -v "${IDENTITY_FILE}:/identity.txt:ro" \
    backup --decrypt --identity /identity.txt "/restore/${archive_name}")

echo '[1/6] Validating encrypted archive and pg_restore catalog...'
"${decrypt[@]}" | "${compose[@]}" exec -T postgres pg_restore --list >/dev/null

echo '[2/6] Stopping the server to prevent writes...'
"${compose[@]}" stop server

echo '[3/6] Preserving the current database and creating a clean target...'
"${compose[@]}" exec -T postgres sh -ec '
case "$POSTGRES_DB$POSTGRES_USER" in *[!A-Za-z0-9_]*) echo "Unsafe database identifier" >&2; exit 1;; esac
previous="${POSTGRES_DB}_pre_restore_$1"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '\''$POSTGRES_DB'\'' and pid <> pg_backend_pid()"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "alter database \"$POSTGRES_DB\" rename to \"$previous\""
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "create database \"$POSTGRES_DB\" owner \"$POSTGRES_USER\""
' restore "$TIMESTAMP"

echo '[4/6] Streaming decrypted data directly into PostgreSQL...'
"${decrypt[@]}" | "${compose[@]}" exec -T postgres sh -ec \
    'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"'

echo '[5/6] Applying migrations and refreshing the backup role...'
"${compose[@]}" run --rm migrate
"${compose[@]}" run --rm backup-bootstrap

echo '[6/6] Starting SmartupCMS and waiting for readiness...'
"${compose[@]}" up -d --wait --wait-timeout "$HEALTH_TIMEOUT_SECONDS"
echo "Restore completed. Previous database suffix: _pre_restore_${TIMESTAMP}."
