#!/usr/bin/env bash
# День 2: деплой демо-клиента (PG + app v1) и регистрация job'а миграций.
# ЗАМЕР: время от запуска скрипта до ответа приложения.
set -euo pipefail
cd "$(dirname "$0")/../nomad"

START=$(date +%s)

echo "== Регистрируем job миграций (parameterized) =="
nomad job run migrate-demo.nomad.hcl

echo "== Деплой client-demo: app v1, ожидаемая схема 1 =="
nomad job run -var app_version=1 -var expected_schema=1 client-demo.nomad.hcl

echo "== Миграция до схемы 1 =="
"$(dirname "$0")/../scripts/03-migrate.sh" 1

echo "== Ждём здоровый app =="
for i in $(seq 1 60); do
  ADDR=$(nomad service info -json demo-app 2>/dev/null \
        | grep -oE '"Address":"[^"]+"|"Port":[0-9]+' | head -2 \
        | sed 's/"Address":"//;s/"//;s/"Port"://' | paste -sd: -) || true
  if [ -n "${ADDR:-}" ] && curl -sf "http://${ADDR}/" | grep -q "demo app v1"; then
    ELAPSED=$(( $(date +%s) - START ))
    echo ""
    echo "=== OK: клиент развёрнут за ${ELAPSED}s — ответ: $(curl -s http://${ADDR}/) ==="
    exit 0
  fi
  sleep 5
done
echo "FAIL: приложение не стало здоровым за 5 минут"; exit 1
