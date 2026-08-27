#!/usr/bin/env bash
# Canary-обновление приложения: ./04-canary-update.sh <version>
# Порядок релиза (ADR-0007): СНАЧАЛА 03-migrate.sh до той же версии, ПОТОМ этот скрипт.
# ЗАМЕР: от запуска до promote.
set -euo pipefail
V="${1:?использование: 04-canary-update.sh <версия>}"
cd "$(dirname "$0")/../nomad"

START=$(date +%s)
echo "== Canary: app v${V}, ожидаемая схема ${V} =="
nomad job run -var "app_version=${V}" -var "expected_schema=${V}" client-demo.nomad.hcl

echo "== Ждём здоровую canary =="
DEPLOY=$(nomad job deployments -json client-demo | grep -m1 '"ID"' | cut -d'"' -f4)
for i in $(seq 1 40); do
  ST=$(nomad deployment status "${DEPLOY}" | awk '/^Status/{print $3}' | head -1)
  case "${ST}" in
    running)
      if nomad deployment status "${DEPLOY}" | grep -q "Canaries.*1/1.*healthy\|healthy.*true"; then
        echo "== Canary здорова — PROMOTE =="
        nomad deployment promote "${DEPLOY}"
        ELAPSED=$(( $(date +%s) - START ))
        echo "=== OK: v${V} промоутнута за ${ELAPSED}s ==="
        exit 0
      fi
      sleep 5 ;;
    failed)
      echo "=== CANARY ПРОВАЛИЛАСЬ — auto_revert вернул прежнюю версию (см. 05-drill) ==="
      exit 1 ;;
    successful)
      echo "=== OK: деплой завершён ==="; exit 0 ;;
    *) sleep 5 ;;
  esac
done
echo "FAIL: canary не разрешилась за таймаут"; exit 1
