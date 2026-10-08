# RB-07: Пул соединений и диск

**Версия:** 1.0

**Обновлено:** 2026-10-07

**Алерты:** `SmartupcmsDbPoolStarved`, `SmartupcmsDiskSpaceCritical` (critical);
`SmartupcmsDbPoolSaturated`, `SmartupcmsDiskSpaceLow` (warning) —
[SLO и алерты](../ops/slo.md).

## Сигнал и цель

- `DbPoolSaturated`: пул `pool` занят больше 90 % 10 мин.
- `DbPoolStarved`: потоки 5 мин ждут соединения — запросы встают в очередь и
  уходят в таймаут.
- `DiskSpaceLow` / `Critical`: на `path` (рабочий каталог или файловое
  хранилище на томе данных сервера) свободно меньше 20 % / 10 %; на томе
  данных лежат также журнал сервера и архив аудита.

Цель — вернуть запас, не потеряв данные.

## Немедленные действия

1. Пул: дашборд «database and disk» — какой пул (основной
   `SmartupCmsHikariPool` на 20 соединений или пул хранилища) и с какого
   момента; совпадает ли с импортом, отчётом, деплоем.
2. Диск: `df -h` на хосте для тома `server-data`; что растёт — `logs`,
   `storage`, `audit-archive`.

## Диагностика

1. Долгие транзакции и блокировки в PostgreSQL (оператор, через
   аутентифицированную сессию):

   ```sql
   select pid, state, now() - xact_start as age, wait_event_type, left(query, 120)
     from pg_stat_activity where datname = current_database() and state <> 'idle'
    order by xact_start nulls last limit 20;
   ```

2. Журнал сервера: `57014` (statement timeout), idle-in-transaction,
   `Connection is not available, request timed out`.
3. Один узел слушает `NOTIFY smc_cache` и держит одно соединение пула —
   это норма, не утечка.
4. Диск: ротация журналов (`SMC_LOG_*`), срок архивов аудита
   (`SMC_AUDIT_ARCHIVE_RETENTION`), объём загрузок.

## Восстановление

- Пул: устраните долгий запрос или транзакцию (исправление кода, индекс новой
  миграцией); перезапуск `server` освобождает соединения, но не лечит причину.
- Диск: увеличьте том; удаляйте только то, что разрешено политикой хранения
  (старые архивы журналов). Не удаляйте файлы хранилища и тома PostgreSQL.
  Диски хоста и PostgreSQL сервер не видит — для них нужен node_exporter.

## Эскалация и закрытие

Ожидание соединения дольше 15 мин или диск ниже 5 % — эскалация владельцу
установки (P1/P2). Закрытие: запас восстановлен, причина записана.
