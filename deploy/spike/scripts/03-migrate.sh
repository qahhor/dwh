#!/usr/bin/env bash
# Миграция до целевой версии схемы: ./03-migrate.sh <target>
# Возвращает 0 при успехе, 1 при провале миграции (это ШТАТНЫЙ исход учения дня 4).
set -euo pipefail
TARGET="${1:?использование: 03-migrate.sh <target-версия-схемы>}"

echo "== Dispatch migrate-demo target=${TARGET} =="
DISPATCH=$(nomad job dispatch -meta "target=${TARGET}" -detach migrate-demo | awk '/Dispatched Job ID/{print $NF}')
echo "   dispatched: ${DISPATCH}"

START=$(date +%s)
for i in $(seq 1 60); do
  STATUS=$(nomad job status "${DISPATCH}" | awk '/^Status/{print $3}' | head -1)
  case "${STATUS}" in
    dead)
      # dead = завершился; успех/провал различаем по allocation'у
      if nomad job status "${DISPATCH}" | grep -qE "failed"; then
        echo ""
        echo "=== МИГРАЦИЯ ПРОВАЛЕНА (target=${TARGET}) — деплой приложения ЗАПРЕЩЁН ==="
        echo "Логи: nomad alloc logs -job ${DISPATCH} flyway"
        exit 1
      fi
      ELAPSED=$(( $(date +%s) - START ))
      echo "=== OK: схема на версии ${TARGET} за ${ELAPSED}s ==="
      exit 0
      ;;
    *) sleep 3 ;;
  esac
done
echo "FAIL: миграция не завершилась за 3 минуты"; exit 1
