# Architecture audit — 2026-08-31

## Контекст и карта системы

GreenWhite — изолированная клиентская платформа: административное ядро, задачи/проекты, файлы, аудит, аналитика и dashboard; отдельный Control Plane управляет fleet клиентов. Назначение сформулировано в `README.md:1-5`, физическая изоляция — database-per-tenant в `README.md:21`.

```text
web-instance (Angular 22) ---> instance API (Spring Boot 4.1 / Java 25)
                                   |-- PostgreSQL client DB + Flyway
                                   |-- Typesense
                                   |-- SMTP / Telegram / webhook / file storage
                                   +-- heartbeat ---> control-plane API

web-cp (Angular 22) ----------> control-plane API ---> PostgreSQL CP DB + Flyway
```

- Maven-модули: `libs/core-types`, `libs/platform-common`, `libs/provider-spi`, `apps/instance`, `apps/control-plane` (`pom.xml:35-40`).
- Instance-домены: `kauth`, `md`, `ms`, `mf`, `audit`, `kwh`, analytics/report; маршруты UI — `apps/web-instance/src/app/app.routes.ts:5-73`.
- CP-домены: fleet, clients, modules, backups, announcements; маршруты — `apps/web-cp/src/app/app.routes.ts:4-23`.
- Данные: PostgreSQL + Flyway (instance V001–V017, CP V001–V005 в текущем workspace), Typesense, локальные/S3-совместимые файлы через SPI. Очередь сообщений не найдена; delivery использует PostgreSQL outbox.
- Локальный запуск: миграции отдельно, затем compose — `README.md:35-70`; CI — Maven verify, Angular builds, clean-compose E2E и security scan (`.github/workflows/ci.yml:16-166`). CD workflow не найден.

## Оценка

Архитектурная база разумная: модульный монолит, явные domain packages, Flyway, outbox, server-side RBAC и ArchUnit. Однако текущий workspace уже нарушает собственную границу controller → service → repository, а одноузловые предположения для idempotency/rate limit/SSE/outbox нигде не закреплены как deployment invariant.

| Наблюдение | Риск | Доказательство | Минимальная рекомендация | Усилие | Приоритет |
|---|---|---|---|---|---|
| Архитектурный gate падает: controller читает repository напрямую | Размывание слоя, обход бизнес-правил и нестабильный CI | `ModularArchitectureTest.java:47-62`; `SystemLicenseController.java:21-45`; результат в `audit/evidence/verification-2026-08-31.md` | Добавить `SystemLicenseService`, перенести `schemaVersion()` туда; gate должен стать зелёным | S | P0 |
| Idempotency реализована как `find → execute → save`; конфликт INSERT игнорируется | Два параллельных запроса с одним ключом оба выполнят side effect | `IdempotencyFilter.java:68-104`; `IdempotencyRepository.java:46-58` | Атомарный claim `PENDING/COMPLETE`, ключ связать с user+method+path; concurrency integration test | M | P1 |
| Outbox worker выбирает `FOR UPDATE SKIP LOCKED`, но orchestration не транзакционная | После завершения SELECT lock освобождается; две реплики могут доставить событие дважды | `MsOutboxRepository.java:51-64`; `KwhOutboxRepository.java:36-51`; соответствующие worker methods без `@Transactional` | Атомарный lease/claim `PROCESSING` с expiry либо явно enforce `replicas=1` до исправления | M | P1 |
| Rate-limit и SSE registry process-local | При масштабировании ограничения и подписки становятся непредсказуемыми | `RateLimitService.java:14-26`; `MsSseRegistry.java:16-30` | Для текущего релиза закрепить один instance replica; распределённое состояние — только при реальной потребности | S | P1 |
| Глобальные компоненты имеют чрезмерную связность | Малое изменение даёт широкую регрессию | `graphify-out/GRAPH_REPORT.md:218-228`: `RequiresPermission` 141 связей, `TasksComponent` 71, `ApiService` 48 | Не переписывать систему; выделять только новые/изменяемые use cases и дробить UI mega-components по сценариям | M | P2 |
| Import cycles не обнаружены, правила слоя формализованы | Снижает архитектурный риск | `graphify-out/GRAPH_REPORT.md:242-243`; `ModularArchitectureTest.java:39-80` | Сохранить ArchUnit обязательным gate и исправлять нарушения до merge | S | P1 |

## Неизвестно / не подтверждено

- Нет подтверждённого C4/deployment diagram для фактического production fleet; `docs/ops/architecture-overview.md` полезен, но не описывает TLS termination и recovery.
- Не найден ADR, фиксирующий максимальное число реплик до распределения process-local state.
- Не найдены contract tests между Instance и Control Plane и формальный version-compatibility matrix.
- Схема custom modules/SSO находится в untracked V017, поэтому её релизный статус не определён.
