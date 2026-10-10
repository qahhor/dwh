# RB-11: Состояние PostgreSQL

**Версия:** 1.0

**Обновлено:** 2026-10-10

**Алерты:** `SmartupcmsPostgresConnectionsExhausted` (critical);
`SmartupcmsPostgresConnectionsHigh`, `SmartupcmsPostgresDatabaseGrowthHigh`,
`SmartupcmsPostgresDeadTuplesHigh`, `SmartupcmsPostgresExporterDown`
(warning) — [SLO и алерты](../ops/slo.md), раздел 7. Метрики даёт
необязательный postgres-exporter
(`deploy/observability/docker-compose.observability.yml`) под ролью
мониторинга с правами `pg_monitor`.

## Сигнал и цель

- `ConnectionsHigh` / `Exhausted`: занято больше 80 % 10 мин / 95 % 5 мин
  `max_connections`. Соединения держат пулы сервера (основной и
  хранилища, на каждый узел), migrate, бэкап и сессии операторов; при
  исчерпании новые сессии, миграции и бэкап получают отказ.
- `DatabaseGrowthHigh`: база `datname` больше 1 ГиБ и выросла больше чем
  наполовину за сутки.
- `DeadTuplesHigh`: у таблицы больше 100 000 мёртвых строк, и они
  составляют больше 20 % строк дольше часа — autovacuum не успевает.
- `ExporterDown`: экспортер 10 мин не опрашивается или не может войти в
  PostgreSQL (`pg_up = 0`) — остальные алерты этого runbook слепы.

Репликации в поставке Docker Compose нет, поэтому алертов отставания
реплики нет; свежесть бэкапа контролирует сервер ([RB-03](RB-03-backup.md)).

## Немедленные действия

1. Дашборд «database and disk», панели PostgreSQL: соединения против
   `max_connections`, размер баз, мёртвые строки.
2. `Exhausted`: сверьте с `SmartupcmsDbPoolStarved`
   ([RB-07](RB-07-database-pool-and-disk.md)) — если пул сервера ждёт
   соединений, источник вне сервера или узлов больше, чем рассчитано.
3. `ExporterDown`: журнал `postgres-exporter`; частая причина — роль
   мониторинга не создана, пароль в файле секрета не совпадает или файл
   секрета не читается пользователем контейнера (UID 65534).

## Диагностика

1. Кто держит соединения (аутентифицированная сессия оператора):

   ```sql
   select usename, application_name, state, count(*)
     from pg_stat_activity group by 1, 2, 3 order by 4 desc;
   ```

   Сумма пулов: `узлы × (основной пул + пул хранилища)` плюс migrate, бэкап,
   экспортер (лимит роли — 3) должна оставаться ниже `max_connections`.
2. Рост базы:

   ```sql
   select relname, pg_size_pretty(pg_total_relation_size(c.oid))
     from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname not in ('pg_catalog', 'information_schema') and c.relkind in ('r', 'p')
    order by pg_total_relation_size(c.oid) desc limit 20;
   ```

   Ожидаемые источники: журнал аудита до ночного архива, сырой слой DWH после
   загрузки УПЛ, журналы до очистки ([RB-08](RB-08-maintenance-tasks.md)).
3. Мёртвые строки: `select relname, n_dead_tup, last_autovacuum,
   last_autoanalyze from pg_stat_user_tables order by n_dead_tup desc limit
   20;` — долгая транзакция (`pg_stat_activity.xact_start`) не даёт vacuum
   убрать строки.

## Восстановление

- Соединения: завершите зависшие сессии операторов
  (`pg_terminate_backend` после проверки), уменьшите пулы или число узлов;
  поднимать `max_connections` — только с пересчётом памяти PostgreSQL.
- Рост: архив аудита и очистка журналов по расписанию; неожиданный рост —
  найти задание или импорт, остановить его, сообщить разработке.
- Мёртвые строки: устраните долгую транзакцию; `VACUUM (ANALYZE)` таблицы
  в окно низкой нагрузки; постоянная проблема — настройка autovacuum для
  таблицы новой миграцией.
- Экспортер: создайте роль мониторинга по SQL из
  [SLO и алерты](../ops/slo.md), раздел 7, и проверьте файл секрета.

## Эскалация и закрытие

`Exhausted` дольше 15 мин или отказ migrate/бэкапа по соединениям —
эскалация владельцу установки (P1). Закрытие: показатель в норме, причина
записана.
