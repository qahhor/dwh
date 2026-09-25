#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 <backup.dump.age> <age-identity-file> [<dwh-backup.dump.age>]" >&2
    echo "The DWH archive is the smartupcms_dwh-<timestamp>.dump.age of the same set." >&2
    exit 1
fi

BACKUP_FILE="$(realpath "$1")"
IDENTITY_FILE="$(realpath "$2")"
CHECKSUM_FILE="${BACKUP_FILE}.sha256"
DWH_BACKUP_FILE=""
[[ $# -eq 3 ]] && DWH_BACKUP_FILE="$(realpath "$3")"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
TIMESTAMP="$(date -u +'%Y%m%dT%H%M%SZ')"
compose=(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE")

verify_checksum() {
    local archive="$1" expected_hash actual_hash
    [[ -f "$archive" ]] || { echo "[ERROR] Backup file $(basename "$archive") does not exist." >&2; exit 1; }
    [[ -f "${archive}.sha256" ]] || { echo "[ERROR] Checksum of $(basename "$archive") does not exist." >&2; exit 1; }
    expected_hash="$(awk 'NR == 1 {print tolower($1)}' "${archive}.sha256")"
    actual_hash="$(sha256sum "$archive" | awk '{print tolower($1)}')"
    [[ -n "$expected_hash" && "$expected_hash" == "$actual_hash" ]] \
        || { echo "[ERROR] SHA-256 verification of $(basename "$archive") failed." >&2; exit 1; }
}

decrypt() {
    local archive="$1"
    "${compose[@]}" run --rm --no-deps --entrypoint age \
        -v "$(dirname "$archive"):/restore:ro" -v "${IDENTITY_FILE}:/identity.txt:ro" \
        backup --decrypt --identity /identity.txt "/restore/$(basename "$archive")"
}

[[ -f "$IDENTITY_FILE" ]] || { echo "[ERROR] age identity file does not exist." >&2; exit 1; }
[[ -f "$ENV_FILE" ]] || { echo "[ERROR] Environment file does not exist." >&2; exit 1; }
verify_checksum "$BACKUP_FILE"
[[ -z "$DWH_BACKUP_FILE" ]] || verify_checksum "$DWH_BACKUP_FILE"

"${compose[@]}" config --quiet
[[ -n "$("${compose[@]}" ps -a -q postgres)" ]] \
    || { echo '[ERROR] PostgreSQL is not running.' >&2; exit 1; }

echo '[1/6] Validating encrypted archives and pg_restore catalogs...'
decrypt "$BACKUP_FILE" | "${compose[@]}" exec -T postgres pg_restore --list >/dev/null
if [[ -n "$DWH_BACKUP_FILE" ]]; then
    decrypt "$DWH_BACKUP_FILE" | "${compose[@]}" exec -T postgres pg_restore --list >/dev/null
else
    echo '[WARN] No DWH archive given: the DWH database is left as it is.' >&2
fi

echo '[2/6] Stopping the server to prevent writes...'
"${compose[@]}" stop server

echo '[3/6] Preserving the current databases and creating clean targets...'
"${compose[@]}" exec -T postgres sh -ec '
case "$POSTGRES_DB$POSTGRES_USER" in *[!A-Za-z0-9_]*) echo "Unsafe database identifier" >&2; exit 1;; esac
previous="${POSTGRES_DB}_pre_restore_$1"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '\''$POSTGRES_DB'\'' and pid <> pg_backend_pid()"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "alter database \"$POSTGRES_DB\" rename to \"$previous\""
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "create database \"$POSTGRES_DB\" owner \"$POSTGRES_USER\""
' restore "$TIMESTAMP"
if [[ -n "$DWH_BACKUP_FILE" ]]; then
    # The application role owns the DWH (ADR-0001): the new database is its own.
    "${compose[@]}" exec -T postgres sh -ec '
case "$DWH_DB_NAME$APP_DB_USER$POSTGRES_USER" in *[!A-Za-z0-9_]*) echo "Unsafe database identifier" >&2; exit 1;; esac
previous="${DWH_DB_NAME}_pre_restore_$1"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '\''$DWH_DB_NAME'\'' and pid <> pg_backend_pid()"
if psql -At -U "$POSTGRES_USER" -d postgres -c "select 1 from pg_database where datname = '\''$DWH_DB_NAME'\''" | grep -q 1; then
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "alter database \"$DWH_DB_NAME\" rename to \"$previous\""
fi
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "create database \"$DWH_DB_NAME\" owner \"$APP_DB_USER\""
' restore "$TIMESTAMP"
fi

echo '[4/6] Streaming decrypted data directly into PostgreSQL...'
decrypt "$BACKUP_FILE" | "${compose[@]}" exec -T postgres sh -ec \
    'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
if [[ -n "$DWH_BACKUP_FILE" ]]; then
    # --role makes the application role own what is restored, as migrations left it.
    decrypt "$DWH_BACKUP_FILE" | "${compose[@]}" exec -T postgres sh -ec \
        'exec pg_restore --exit-on-error --no-owner --no-acl --role="$APP_DB_USER" -U "$POSTGRES_USER" -d "$DWH_DB_NAME"'
fi

echo '[5/6] Applying migrations and refreshing the backup role...'
"${compose[@]}" run --rm migrate
"${compose[@]}" run --rm backup-bootstrap

echo '[6/6] Starting SmartupCMS and waiting for readiness...'
"${compose[@]}" up -d --wait --wait-timeout "$HEALTH_TIMEOUT_SECONDS"
echo "Restore completed. Previous database suffix: _pre_restore_${TIMESTAMP}."
