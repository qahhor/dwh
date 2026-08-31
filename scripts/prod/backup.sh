#!/usr/bin/env bash
# Fail-closed backups for the production fleet compose project.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.fleet.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
TARGET="${TARGET:-all}"
TIMESTAMP="$(date -u +"%Y%m%dT%H%M%SZ")"

compose=(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE")

if [[ ! -f "$ENV_FILE" ]]; then
    echo "[ERROR] Environment file '$ENV_FILE' was not found." >&2
    exit 1
fi

if [[ ! "$RETENTION_DAYS" =~ ^[0-9]+$ ]]; then
    echo "[ERROR] RETENTION_DAYS must be a non-negative integer." >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"
"${compose[@]}" config --quiet

backup_database() {
    local service="$1"
    local prefix="$2"
    local backup_file="${BACKUP_DIR}/${prefix}_${TIMESTAMP}.dump"
    local partial_file="${backup_file}.partial"

    if [[ -z "$("${compose[@]}" ps -a -q "$service")" ]]; then
        echo "[ERROR] Database service '$service' does not exist; refusing to claim a backup." >&2
        return 1
    fi

    echo "Backing up '$service' to '$backup_file'..."
    rm -f "$partial_file"
    if ! "${compose[@]}" exec -T "$service" sh -ec \
        'exec pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" --format=custom' > "$partial_file"; then
        rm -f "$partial_file"
        echo "[ERROR] pg_dump failed for '$service'." >&2
        return 1
    fi

    if [[ ! -s "$partial_file" ]]; then
        rm -f "$partial_file"
        echo "[ERROR] Backup for '$service' is empty." >&2
        return 1
    fi

    mv "$partial_file" "$backup_file"
    sha256sum "$backup_file" > "${backup_file}.sha256"
    sha256sum --check "${backup_file}.sha256" >/dev/null
    echo "Backup verified: '$backup_file'."
}

case "$TARGET" in
    all)
        backup_database db instance
        backup_database db-cp control-plane
        ;;
    instance)
        backup_database db instance
        ;;
    cp)
        backup_database db-cp control-plane
        ;;
    *)
        echo "[ERROR] TARGET must be one of: all, instance, cp." >&2
        exit 1
        ;;
esac

find "$BACKUP_DIR" -type f \( -name '*.dump' -o -name '*.dump.sha256' \) \
    -mtime "+$RETENTION_DAYS" -delete
echo "Backup lifecycle completed successfully."
