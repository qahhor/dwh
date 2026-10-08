# RB-05: Очередь заданий отстаёт или задания провалены

**Версия:** 1.0

**Обновлено:** 2026-10-07

**Алерты:** `SmartupcmsJobQueueStalled` (critical); `SmartupcmsJobQueueLagHigh`,
`SmartupcmsJobsFailed` (warning) — [SLO и алерты](../ops/slo.md).

## Сигнал и цель

- `JobQueueLagHigh` / `JobQueueStalled`: самое старое задание, срок которого
  наступил и которое никто не арендовал, ждёт дольше 5 / 15 мин. Не работают
  разбор и применение загрузок, выгрузки, обслуживание.
- `JobsFailed`: за 30 мин появились задания, исчерпавшие попытки
  (`fnd_job_queue.failed_at`).

Цель — запустить очередь и разобрать провалы, не потеряв загрузки.

## Немедленные действия

1. Проверьте, включена ли очередь: `jobs_enabled` в глобальных настройках
   (`md_settings`, `user_id is null`); `false` останавливает все экземпляры.
2. Живы ли экземпляры (`up`, readiness — [RB-01](RB-01-instance-unavailable.md)).

## Диагностика

1. Дашборд «jobs and outbox»: «Job attempts by handler» — работает ли кто-то;
   «Job run time p95 by handler» — не держит ли один обработчик всех.
2. Журнал: `job_tick_failed`, `job_lease_renewal_failed`,
   `job_outcome_not_recorded_lease_lost`, `job_failed handler=…`.
3. Провалы и их ошибки (запрос из раздела «Background jobs and database
   timeouts» [операционного runbook](../ops/operations-runbook.md)):

   ```sql
   select q.id, q.handler, q.attempts, q.failed_at, r.error
     from fnd_job_queue q
     join lateral (select error from fnd_job_runs where queue_id = q.id order by id desc limit 1) r on true
    where q.failed_at is not null;
   ```

4. Зависимости обработчиков: pg-dwh и хранилище для `upl.parse`/`upl.apply`,
   S3 для выгрузок.

## Восстановление

- Очередь выключена — включите `jobs_enabled`.
- Экземпляр умер с арендой — задание возьмёт другой узел по истечении
  `SMC_JOBS_LEASE` (5 мин); ничего делать не нужно.
- После исправления причины верните провал в очередь (тот же раздел runbook):
  `update fnd_job_queue set failed_at = null, attempts = 0, next_run_at = now() where id = <id>;`

## Эскалация и закрытие

Очередь стоит дольше часа или провалено применение загрузки — эскалация
владельцу установки. Закрытие: отставание ниже 5 мин, провалы разобраны или
повторены, причина записана.
