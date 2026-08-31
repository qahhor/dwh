# Performance and reliability audit — 2026-08-31

## Ограничение вывода

Production traces, SLO, нагрузочный профиль, representative dataset и load tests не найдены. Поэтому p95/RPS/capacity не подтверждаются; ниже только доказуемые code/config risks.

| Наблюдение | Риск | Доказательство | Минимальная рекомендация / критерий | Усилие | Приоритет |
|---|---|---|---|---|---|
| PostgreSQL fallback search делает `%query% ILIKE` по users/tasks/projects; trigram indexes для этих полей не найдены | Sequential scans и рост latency/DB CPU с объёмом данных | `SearchService.java:58-120`; migrations включают `pg_trgm`, но соответствующих GIN/GiST индексов не найдено | EXPLAIN ANALYZE на representative data; либо mandatory Typesense, либо точечные trigram indexes по доказанным query patterns | M | P1 |
| Webhook HTTP client без явных connect/read timeout | Worker может зависнуть и задержать outbox | `KwhOutboxWorker.java:25-57`; в отличие от `TypesenseClient.java:36-45` | Явные короткие timeout, bounded retry+jitter, metrics; integration test с slow endpoint | S | P1 |
| Initial web-instance bundle 474.13 kB близок к warning budget 500 kB; tasks lazy chunk ~203 kB | Малый запас до build regression и медленный first use tasks на слабых устройствах | фактический build в `audit/evidence/verification-2026-08-31.md`; Angular budgets в `angular.json` | Сделать budget gate текущим baseline и профилировать tasks chunk; удалить только доказанный dead/heavy import | M | P1 |
| Новая analytics агрегация не имеет load evidence | Full aggregates могут конкурировать с OLTP | `apps/instance/src/main/java/.../analytics/**`; `V016__analytics_schema.sql` содержит базовые индексы | EXPLAIN на production-like volume; timeout и date-range cap; cache только если измерен repeated-query benefit | M | P1 |
| Hikari/virtual threads заданы, но capacity не валидирована | Virtual threads могут увеличить конкуренцию за пул/DB вместо throughput | `application.yml:8-22` (Hikari 20); CP config pool 10 | Load test critical read/write mix; monitor pool pending, DB connections, p95/error rate; документировать max replicas | M | P1 |
| Keyset pagination и worker indexes уже применяются | Хорошая база для больших списков/outbox | repositories/migrations с cursor predicates и partial indexes | Сохранить; добавить query-plan regression для top 5 queries | S | P2 |
| Нет performance/SLO gate | Регрессия обнаружится только пользователем | load-test scripts, dashboard и SLO artifacts не найдены; observability ADR unchecked | Pilot baseline: login/task list/search/upload, фиксированный dataset, p50/p95/error rate + resource ceiling; threshold согласовать с product/SLA owner | M | P1 |

## Надёжность

- Retries/timeouts есть для Typesense и SMTP; webhook — исключение.
- Process-local rate limit/SSE и неатомарный outbox claim делают горизонтальное масштабирование небезопасным; до исправления нужен one-replica invariant (`audit/architecture-2026-08-31.md`).
- Backup/restore и deploy health важнее микрооптимизаций и вынесены в P0 DevOps.
