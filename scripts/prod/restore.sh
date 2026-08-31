#!/usr/bin/env bash
# Restore a verified custom-format dump while preserving the previous database.
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <backup.dump>" >&2
    exit 1
fi

BACKUP_FILE="$1"
CHECKSUM_FILE="${BACKUP_FILE}.sha256"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.fleet.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
TARGET="${TARGET:-instance}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
TIMESTAMP="$(date -u +"%Y%m%dT%H%M%SZ")"
REMOTE_FILE="/tmp/dwh-restore-${TIMESTAMP}.dump"
compose=(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE")

if [[ ! -f "$BACKUP_FILE" ]]; then
    echo "[ERROR] Backup file '$BACKUP_FILE' does not exist." >&2
    exit 1
fi
if [[ ! -f "$CHECKSUM_FILE" ]]; then
    echo "[ERROR] Required checksum '$CHECKSUM_FILE' does not exist." >&2
    exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
    echo "[ERROR] Environment file '$ENV_FILE' does not exist." >&2
    exit 1
fi

expected_hash="$(awk 'NR == 1 {print tolower($1)}' "$CHECKSUM_FILE")"
actual_hash="$(sha256sum "$BACKUP_FILE" | awk '{print tolower($1)}')"
if [[ -z "$expected_hash" || "$expected_hash" != "$actual_hash" ]]; then
    echo "[ERROR] Backup SHA-256 verification failed." >&2
    exit 1
fi

case "$TARGET" in
    instance)
        db_service="db"
        workload_service="app"
        migrate_service="migrate"
        ;;
    cp)
        db_service="db-cp"
        workload_service="control-plane"
        migrate_service="migrate-cp"
        ;;
    *)
        echo "[ERROR] TARGET must be one of: instance, cp." >&2
        exit 1
        ;;
esac

"${compose[@]}" config --quiet
if [[ -z "$("${compose[@]}" ps -a -q "$db_service")" ]]; then
    echo "[ERROR] Database service '$db_service' does not exist." >&2
    exit 1
fi

cleanup() {
    "${compose[@]}" exec -T "$db_service" rm -f "$REMOTE_FILE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[1/6] Copying and validating the custom-format dump..."
"${compose[@]}" cp "$BACKUP_FILE" "${db_service}:${REMOTE_FILE}"
"${compose[@]}" exec -T "$db_service" pg_restore --list "$REMOTE_FILE" >/dev/null

echo "[2/6] Stopping '$workload_service' to prevent writes..."
"${compose[@]}" stop "$workload_service"

echo "[3/6] Preserving the current database and creating a clean target..."
"${compose[@]}" exec -T "$db_service" sh -ec '
    case "$POSTGRES_DB$POSTGRES_USER" in
        *[!A-Za-z0-9_]*) echo "Unsafe database identifier" >&2; exit 1 ;;
    esac
    previous="${POSTGRES_DB}_pre_restore_$1"
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
        -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '\''$POSTGRES_DB'\'' and pid <> pg_backend_pid()"
    if psql -At -U "$POSTGRES_USER" -d postgres \
        -c "select 1 from pg_database where datname = '\''$POSTGRES_DB'\''" | grep -q 1; then
        psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
            -c "alter database \"$POSTGRES_DB\" rename to \"$previous\""
    fi
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
        -c "create database \"$POSTGRES_DB\" owner \"$POSTGRES_USER\""
' restore "$TIMESTAMP"

echo "[4/6] Restoring data..."
"${compose[@]}" exec -T "$db_service" sh -ec \
    'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$1"' \
    restore "$REMOTE_FILE"

echo "[5/6] Applying forward migrations..."
"${compose[@]}" run --rm "$migrate_service"

echo "[6/6] Starting '$workload_service' and waiting for readiness..."
"${compose[@]}" up -d --wait --wait-timeout "$HEALTH_TIMEOUT_SECONDS" "$workload_service"

echo "Restore completed. The pre-restore database was retained with suffix '_pre_restore_${TIMESTAMP}'."
