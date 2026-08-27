#!/usr/bin/env bash
# День 4 — учения. Предусловие: система на v2/схема 2 (дни 2–3).
# Часть 1: ломаная миграция V3 падает -> релиз останавливается ДО деплоя.
# Часть 2: принудительный деплой app v3 на схему 2 -> schema-gate валит canary -> auto_revert.
set -uo pipefail
DIR="$(dirname "$0")"

echo "########## ЧАСТЬ 1: ломаная миграция ##########"
if "${DIR}/03-migrate.sh" 3; then
  echo "НЕОЖИДАННО: V3 прошла — учение недействительно, разберитесь"; exit 1
else
  echo "OK (ожидаемо): миграция V3 упала, схема осталась на 2."
  echo "ДЕЙСТВИЕ ДНЯ: проверить flyway_schema_history на failed-строку;"
  echo "зафиксировать в отчёте, нужен ли 'flyway repair' перед следующей попыткой (важно для T-022)."
fi

echo ""
echo "########## ЧАСТЬ 2: app v3 против схемы 2 -> auto_revert ##########"
START=$(date +%s)
if "${DIR}/04-canary-update.sh" 3; then
  echo "НЕОЖИДАННО: canary v3 стала здоровой при схеме 2 — schema-gate не сработал!"; exit 1
else
  ELAPSED=$(( $(date +%s) - START ))
  echo "OK (ожидаемо): canary провалена, auto_revert за ~${ELAPSED}s."
fi

echo ""
echo "== Проверка: система по-прежнему обслуживает v2 =="
ADDR=$(nomad service info -json demo-app 2>/dev/null \
      | grep -oE '"Address":"[^"]+"|"Port":[0-9]+' | head -2 \
      | sed 's/"Address":"//;s/"//;s/"Port"://' | paste -sd: -)
RESP=$(curl -sf "http://${ADDR}/")
echo "   ответ: ${RESP}"
echo "${RESP}" | grep -q "v2" \
  && echo "=== УЧЕНИЯ ПРОЙДЕНЫ: отказы не дошли до пользователя ===" \
  || { echo "FAIL: система не на v2"; exit 1; }
