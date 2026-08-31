#!/usr/bin/env bash
# Production fleet deployment with backup, migration and readiness gates.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.fleet.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
compose=(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE")

on_error() {
    local exit_code=$?
    echo "[ERROR] Deployment failed. Current service state:" >&2
    "${compose[@]}" ps >&2 || true
    exit "$exit_code"
}
trap on_error ERR

if [[ ! -f "$ENV_FILE" ]]; then
    echo "[ERROR] Environment file '$ENV_FILE' was not found." >&2
    exit 1
fi

if [[ ! "$HEALTH_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
    echo "[ERROR] HEALTH_TIMEOUT_SECONDS must be a positive integer." >&2
    exit 1
fi

echo "[1/8] Validating Docker Compose and production configuration..."
docker compose version >/dev/null
"${compose[@]}" config --quiet

echo "[2/8] Pulling immutable application and external runtime images..."
"${compose[@]}" pull --ignore-buildable

echo "[3/8] Building the hardened PostgreSQL runtime..."
"${compose[@]}" build --pull db typesense proxy

echo "[4/8] Creating mandatory backup for an existing fleet..."
instance_db_id="$("${compose[@]}" ps -a -q db)"
cp_db_id="$("${compose[@]}" ps -a -q db-cp)"
if [[ -n "$instance_db_id" || -n "$cp_db_id" ]]; then
    COMPOSE_FILE="$COMPOSE_FILE" ENV_FILE="$ENV_FILE" bash scripts/prod/backup.sh
else
    echo "No existing database containers found; treating this as an initial deployment."
fi

echo "[5/8] Applying instance database migrations..."
"${compose[@]}" run --rm migrate

echo "[6/8] Applying control-plane database migrations..."
"${compose[@]}" run --rm migrate-cp

echo "[7/8] Starting the fleet and waiting for readiness..."
"${compose[@]}" up -d --remove-orphans --wait --wait-timeout "$HEALTH_TIMEOUT_SECONDS"

echo "[8/8] Recording final service state..."
"${compose[@]}" ps

trap - ERR
echo "Deployment completed successfully: migrations passed and all services are ready."
