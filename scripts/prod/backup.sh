#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
compose=(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE")

[[ -f "$ENV_FILE" ]] || { echo "[ERROR] Environment file '$ENV_FILE' was not found." >&2; exit 1; }
"${compose[@]}" config --quiet

if [[ -z "$("${compose[@]}" ps -a -q postgres)" ]]; then
    echo "[ERROR] PostgreSQL is not running; refusing to claim a backup." >&2
    exit 1
fi

echo "Creating an encrypted one-shot backup..."
"${compose[@]}" run --rm --no-deps -e BACKUP_RUN_ONCE=true backup
echo "Encrypted backup completed and status was updated."
