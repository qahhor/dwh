# RB-01: Экземпляр недоступен или не готов

**Версия:** 1.0

**Обновлено:** 2026-10-07

**Алерты:** `SmartupcmsInstanceDown`, `SmartupcmsScrapeTargetMissing`,
`SmartupcmsReadinessDown` (critical) — [SLO и алерты](../ops/slo.md).

**Область:** поставка Docker Compose ([ADR-0014](../adr/ADR-0014-unified-open-source-runtime.md)).

## Сигнал и цель

- `SmartupcmsInstanceDown`: порт управления (9090) не отвечает Prometheus 2 мин —
  процесс упал, завис или недоступен по сети.
- `SmartupcmsScrapeTargetMissing`: у Prometheus нет ни одной цели job
  `smartupcms` — потеряна конфигурация сбора или service discovery.
- `SmartupcmsReadinessDown`: процесс жив, но группа readiness (основная БД) в
  состоянии DOWN 2 мин — экземпляр выведен из трафика.

Цель — вернуть обслуживание, не потеряв данные и улики.

## Немедленные действия

1. Зафиксируйте время UTC, тег релиза, затронутые экземпляры и влияние на
   пользователей; назначьте владельца инцидента (P1).
2. Сохраните журналы до перезапуска:

   ```bash
   docker compose -f deploy/compose/docker-compose.prod.yml \
     --env-file .env.production logs --since 30m server web postgres
   ```

3. Не удаляйте тома и не правьте историю Flyway.

## Диагностика

1. `docker compose … ps` — состояние и healthcheck `server`, `postgres`, `web`.
2. Readiness на порту управления изнутри сети мониторинга:
   `curl -fsS http://<server>:9090/actuator/health/readiness`. DOWN с компонентом
   `database` — основная БД не отвечает за `SMC_SYSTEM_HEALTH_TIMEOUT` (2 с).
3. PostgreSQL: health, свободное место, ошибки файловой системы, число
   соединений (`max_connections`), блокировки.
4. Для `ScrapeTargetMissing` — конфигурация Prometheus (job `smartupcms`,
   `/actuator/prometheus`), сеть между Prometheus и портом 9090.
5. Был ли деплой, провал миграции (сервис `migrate`, [RB-04](RB-04-migration-failure-triage.md))
   или смена тега релиза.

## Восстановление

- Процесс упал или завис, причина понятна — перезапустите только `server`:

  ```bash
  docker compose -f deploy/compose/docker-compose.prod.yml \
    --env-file .env.production up -d --wait server
  ```

- БД недоступна — восстановите PostgreSQL (место на диске, перезапуск
  контейнера после сохранения журналов); readiness вернётся сама, liveness
  процесс не перезапускает.
- Инцидент вызван релизом — [откат](../ops/rollback.md).

## Эскалация и закрытие

Если экземпляр не вернулся за 30 мин или есть признаки порчи данных —
эскалируйте владельцу установки и действуйте по разделу «Service is
unavailable» [операционного runbook](../ops/operations-runbook.md). Закрытие:
алерт погас, readiness UP, причина и хронология записаны в инцидент.
