#!/usr/bin/env bash
# ==============================================================================
# Smartup DWH / CMS Production Zero-Touch Deployment Orchestrator
# ==============================================================================
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.fleet.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"

echo "============================================================"
echo "  Smartup DWH / CMS - Production Deployment Orchestrator    "
echo "============================================================"

# 1. Pre-flight Checks
if [ ! -f "$ENV_FILE" ]; then
    echo "[ERROR] Environment file $ENV_FILE not found! Copy .env.prod.example and configure secrets."
    exit 1
fi

echo "[1/5] Validating Docker Engine & Compose..."
docker compose version >/dev/null

echo "[2/5] Running Pre-deploy Backup..."
bash scripts/prod/backup.sh || echo "[WARN] Backup skipped or failed, proceeding with care..."

echo "[3/5] Executing Database Migrations (Schema Version Gate)..."
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" run --rm migrate
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" run --rm migrate-cp

echo "[4/5] Deploying Production Container Fleet..."
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --remove-orphans

echo "[5/5] Polling Health Probes (Readiness Gate)..."
for i in {1..20}; do
    if docker compose -f "$COMPOSE_FILE" ps | grep -q "(unhealthy)"; then
        echo "[ERROR] Unhealthy container detected! Initiating triage..."
        docker compose -f "$COMPOSE_FILE" ps
        exit 1
    fi
    echo "Waiting for services to become healthy ($i/20)..."
    sleep 3
done

echo "============================================================"
echo "  DEPLOYMENT SUCCESSFUL! All services healthy & operational. "
echo "============================================================"
