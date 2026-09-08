# Performance / scalability audit — SmartupCMS — 2026-09-05

> Архивный непроверенный черновик. Сохранён в Git по запросу пользователя
> 2026-09-08; публикация не подтверждает выводы, результаты проверок или
> актуальность находок. Не использовать как требования или release evidence.

База `710efeb`; [общая методика и ограничения](D:/Claude/dwh/audit/cto-audit-2026-09-05.md). Статический bottleneck — гипотеза о поведении под нагрузкой, если ниже явно не указано измерение. Production SQL/индексы не изменялись.

## Hot-path map

| Путь | Наблюдение | Первичный артефакт |
|---|---|---|
| Каждый активный cookie request | Session SELECT → user SELECT → session UPDATE → permissions SELECT → version SELECT ещё до endpoint | [AuthenticationFilter:80](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/security/KauthAuthenticationFilter.java:80), [MdPermissionRepository:97](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/md/repository/MdPermissionRepository.java:97) |
| Task detail | Отдельные чтения task, members, subtasks, ancestors, files, плюс markViewed | [MsTaskController:140](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/controller/MsTaskController.java:140) |
| Task list/search | Keyset по id; substring ILIKE title/description | [MsTaskRepository:76](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/repository/MsTaskRepository.java:76) |
| File upload | Quarantine upload, download для scanner, copy в final key, short metadata transaction | [MfFileService:42](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileService.java:42) |
| Search startup | Полные result sets + по одному upsert | [TypesenseSyncRunner:65](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseSyncRunner.java:65) |
| Audit stats | Два global COUNT + два 24h COUNT без cache | [AuditLogRepository:206](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/audit/repository/AuditLogRepository.java:206) |
| Export | Full result read внутри @Transactional, поток ответа держит путь выполнения | [ReportService:15](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/report/service/ReportService.java:15) |

Классический N+1 на task list в проверенном repository не обнаружен: не следует путать несколько запросов detail endpoint с N запросами на каждую строку списка. Query-count test нужен, прежде чем обещать устранение N+1.

## P-01. Пять auth SQL операций на запрос и лишние записи активности

**P1 / S–M (1–2 дня базовая оптимизация), change risk medium.**

Для активной cookie-сессии путь выше содержит **5 SQL statements** до business query, не считая BEGIN/COMMIT/driver internals. `last_seen_at = now()` обновляется при каждом запросе: [KauthSessionRepository:72](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/repository/KauthSessionRepository.java:72). Bearer имеет аналогичное обновление last used. Сервисы permissions/version не кешируются. Это статический count, не measurement throughput.

Риск: WAL/row-lock contention на одной сессии, round-trip amplification и насыщение Hikari 20 connections при чтениях. [application.yml:24](D:/Claude/dwh/apps/server/src/main/resources/application.yml:24) задаёт pool 20, idle 5, ожидание до 20 секунд; virtual threads не увеличивают ёмкость БД.

Минимум: auth projection объединяет необходимые reads; activity update coalescing с небольшим явно заданным интервалом, сохранение авторитетной проверки block/revoke. Вариант SQL для сокращения **записей**, ещё без уменьшения числа запросов:

```sql
UPDATE kauth_sessions
SET last_seen_at = now()
WHERE id = :sessionId
  AND closed_at IS NULL
  AND last_seen_at < now() - interval '60 seconds';
```

60 секунд — предлагаемый, не утверждённый параметр; согласовать с session inactivity policy. Сначала измерить before/after query count и WAL. Если нужен permissions cache — ключ `(userId, permissionsVersion)`, bounded размер и гарантированная invalidation; нельзя применять длинный TTL к revoked access.

Приёмка: активная cookie request укладывается в утверждённый auth SQL budget, права/блокировка отзываются сразу; session timeout допускает только согласованную погрешность активности. До/после тест одинаковых запросов на одной сессии. [AuthenticationFilter:73/94](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/security/KauthAuthenticationFilter.java:73) глотает `Exception`: различать invalid credentials и DB outage, последний считать dependency error/503 и метрикой, не ложным logout.

## P-02. Подтверждённые дубли индексов; поиск требует репрезентативного EXPLAIN

**P1 / S (<1 дня для duplicate indexes), change risk low/medium.**

[V001:280](D:/Claude/dwh/apps/server/src/main/resources/db/migration/V001__init_schema.sql:280) создаёт `ms_tasks_project_idx(project_id)` и `ms_tasks_status_idx(status_id)`. [V016:7](D:/Claude/dwh/apps/server/src/main/resources/db/migration/V016__analytics_schema.sql:7) создаёт ещё `idx_ms_tasks_status_id(status_id)` и `idx_ms_tasks_project_id(project_id)`. `IF NOT EXISTS` проверяет имя, не эквивалентность индекса. В текущем `pg_indexes` обе пары действительно существуют как одинаковые btree indexes.

Риск: лишние index updates, WAL, дисковое место и обслуживание; выигрыш неизвестен до замера write workload. Кандидат **новой**, не применённой миграции:

```sql
-- Только после проверки pg_index / pg_constraint зависимостей и сохранения
-- ms_tasks_project_idx / ms_tasks_status_idx. Не переписывать V001/V016.
DROP INDEX CONCURRENTLY IF EXISTS idx_ms_tasks_status_id;
DROP INDEX CONCURRENTLY IF EXISTS idx_ms_tasks_project_id;
```

CONCURRENTLY нельзя оборачивать в transaction: выполнить контролируемый operator step либо отдельную Flyway migration с корректным non-transactional mode. Перед production проверить поведение Flyway и locks на disposable DB. Rollback: CREATE INDEX CONCURRENTLY тех же имён/определений, сохранённые original indexes продолжают обслуживать запросы. Критерий: нет equivalent duplicate pair, планы ключевых запросов не ухудшились, query results неизменны.

### Свежий read-only EXPLAIN

На локальной БД `SELECT count(*) FROM ms_tasks` = **9**. В read-only transaction выполнено:

```sql
EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)
SELECT t.id FROM ms_tasks t
WHERE t.title ILIKE '%audit%'
   OR t.description_markdown ILIKE '%audit%'
ORDER BY t.id LIMIT 51;
```

Результат: `Limit → Sort → Seq Scan`; rows removed by filter 9; actual result 0; shared hit 4; planning 13.355 ms; execution **0.221 ms**. Один такой план на девяти строках **не является плохим планом сам по себе** и не даёт основания срочно добавлять GIN. Индексов pg_trgm на these columns в увиденном `pg_indexes` нет.

Следующий эксперимент: disposable dataset с реальным распределением title/description, scope и селективности; `EXPLAIN (ANALYZE, BUFFERS)` для admin/SELF/UNITS, first/deep keyset pages и selective/broad terms. Лишь при доказанном scan bottleneck оценить отдельные trigram GIN indexes или объединённый индекс выражения; проверить write/space cost. Не заменять scoped SQL глобальным Typesense search без авторизации.

## P-03. Память и backpressure для upload/export/idempotency

**P1; M (часть I-04/I-09), change risk medium.**

Положительно: 50 MiB/file, 51 MiB/request, streaming SPI, quarantine/scanning вне metadata transaction, striped lock memory bounded: [application.yml:9](D:/Claude/dwh/apps/server/src/main/resources/application.yml:9), [MfFileService](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileService.java), [MfFileObjectLock](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileObjectLock.java).

Остаточные риски:

- [IdempotencyFilter:61](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/config/idempotency/IdempotencyFilter.java:61) делает `readAllBytes()` до MVC parsing и кеширует весь response. 20 запросов по ~50 MiB — арифметически около 1 GiB request arrays без overhead; это сценарная оценка, не observed heap. Ограничение multipart resolver нельзя считать общей защитой всех JSON bodies на уровне filter.
- [S3StorageProvider](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/mf/storage/S3StorageProvider.java) stage-ит upload во временный файл; quarantine scanning/copy означает несколько проходов байтов. Production `/tmp` — tmpfs без явного size в [Compose:52](D:/Claude/dwh/deploy/compose/docker-compose.prod.yml:52): одновременно учитывать heap, native memory, page cache/tmpfs, proxy buffers и scanner.
- [ReportService:15](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/report/service/ReportService.java:15) держит read transaction во время записи ответа; явный fetch size/export row/time cap в этом сервисе не задан. Медленный клиент и большой результат могут долго держать connection. Streaming HTTP сам по себе не гарантирует server-side JDBC cursor.

Минимум: idempotency allow-list/body cap, bounded admission для near-limit uploads и больших exports, разделить обычный API timeout и SSE location; закрепить temp/memory budgets и отказ с понятным 413/429/503. Export: сначала S-01, затем scoped keyset chunks или проверенный JDBC cursor/fetch size с лимитом строк/времени и cancellation. Публикацию файлов не возвращать в длинную DB-транзакцию ради простоты.

Приёмка: 20 near-limit uploads + scanner/R2 slow/outage + client abort; не истощается heap/temp/pool, нет опубликованных unscanned objects. Проверить orphan cleanup после process kill, не только catch/finally. Slow export не занимает весь pool; отмена клиента освобождает DB connection.

## P-04. Дорогие reads и startup, cache strategy

**P1 / S–M для stats, M для search; change risk medium.**

[AuditLogRepository:206](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/audit/repository/AuditLogRepository.java:206) выполняет полные COUNT по audit/security и отдельные суточные COUNT. С ростом retention и числа partition растёт работа на каждый refresh. Не подменять точные counts приближёнными без UI-контракта. Минимум — объединить совместимые aggregates и короткий bounded snapshot cache с `computedAt`; TTL 15–30 секунд — предложение, не требование. Проверять только на representative cardinality.

I18n уже имеет cache с проверкой language/Russian revision: [MdI18nService:61](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdI18nService.java:61). Не удалять version validation ради уменьшения запросов; global mutable dictionary без invalidation сломает live editing. Server permission cache отсутствует в проверенном auth path — P-01.

Search startup полные lists + per-document network calls, async race/retry — [A-02](D:/Claude/dwh/audit/architecture-2026-09-05.md). Применить bounded batches, bulk import и durable retry. Не добавлять общий distributed cache для всех таблиц без конкретного hot path.

## P-05. Frontend baseline

Свежие `npm test`, `npm run typecheck`, `npm run build` на pinned Node 24.15.0 завершились exit 0: 107 тестов/31 файл; initial 476.61 kB raw, estimated transfer 126.31 kB; tasks lazy chunk 209.72 kB raw / 39.38 kB estimate. Пороги сборки: warning 500 kB, error 1 MB initial [angular.json:40](D:/Claude/dwh/apps/web/angular.json:40). Следовательно, initial ниже warning, но отдельный тяжёлый tasks module стоит профилировать на реальном устройстве.

Lazy route imports есть: [app.routes](D:/Claude/dwh/apps/web/src/app/app.routes.ts). A11y assertions critical/serious axe существуют: [e2e helper](D:/Claude/dwh/e2e/support/accessibility.ts). Это не доказательство всех экранов на мобильном, screen reader usability, LCP/INP или производительности сети. Не проводился новый визуальный обход: текущая задача — архитектурный аудит. Минимум — измерить login→tasks/detail/settings/system/audit на целевом mobile/desktop/network profile; сокращать payload/change detection только после trace.

## Capacity / performance acceptance

Нужно разделить две модели: 100 active users **по парку** и worst-case load одной установки. [ТЗ:42](D:/Claude/dwh/docs/technical-specification.md:42). 100 одновременных пользователей ≠ 100 RPS. Test scripts [run-capacity](D:/Claude/dwh/scripts/acceptance/run-capacity.ps1) уже существуют, но результаты на целевой инфраструктуре не предоставлены.

Предлагаемые метрики, **без выдуманных утверждённых thresholds**:

| Метрика | Как измерять и принимать |
|---|---|
| API p50/p95/p99 по auth/task/file/audit/export | Отдельные route labels; согласовать числовой бюджет, не сводить upload и GET в один percentile |
| Error rate | 5xx/timeout отдельно от ожидаемых 401/403/409/429; agreed threshold и error budget |
| DB saturation | Hikari active/pending/acquire timeout, SQL count/request, slow query time, locks/WAL/IO |
| Upload | p95/p99 на размер файла, scanner/S3 timers, одновременно in-flight, temp bytes и cleanup |
| JVM/host | heap/native/RSS, GC pauses, cgroup OOM, tmpfs/disk/inodes, CPU throttling |
| Async | search lag/backlog/retries; notification/webhook oldest age/dead letters; SSE reconnects |
| Web | LCP/INP/CLS и page/action duration на фиксированном устройстве и сети |
| Recovery | Фактические RPO/RTO и время unhealthy→alert→ack→restore |

Начать с согласованного репрезентативного dataset, затем read/write mix, 20 near-limit uploads и 4h soak. Acceptance: утверждённые budgets выдержаны за всё окно, нет OOM/missing/orphan objects; raw evidence связан с dataset/image digest/host profile. Постоянные resource limits и alert owners — [D-04](D:/Claude/dwh/audit/devops-2026-09-05.md).
