# Architecture audit — 2026-09-02

## System map

SmartupCMS is a single-organization open-source CMS/work-management system. One
Angular 22 web origin proxies to one Spring Boot 4.1 / Java 25 modular monolith;
PostgreSQL 18 is the source of truth, Typesense 27.1 provides search, and file
bytes use local disk or S3-compatible storage (`README.md:14-39`,
`docs/ops/architecture-overview.md:7-60`). The runtime modules are IAM/auth,
master data/RBAC, tasks/projects, files, notifications/announcements, audit,
webhooks, analytics, reporting, search and system status. Payments and a message
broker were not found.

Critical flows confirmed in browser tests are login/logout, navigation,
role/user lifecycle, project→task→comment, file upload/delete, notification
inbox, announcement lifecycle and local System status
(`e2e/tests/browser/**/*.spec.ts`). External boundaries are SMTP/SMS/messenger
providers, webhooks, Typesense and S3-compatible storage. Outbound webhooks are
now disabled by default (`application.yml:106-115`).

## Findings

| Observation | Risk | Evidence | Minimal recommendation | Effort | Priority |
|---|---|---|---|---|---|
| Modular-monolith boundaries are executable and current tests pass. | Low; regressions would otherwise couple controllers/repositories or introduce cycles. | `ModularArchitectureTest.java:40-104` checks cycles, controller→repo, repo→service and md/mf boundaries. | Keep the monolith and ArchUnit rules; do not split services for the forecast load. | S | P2 |
| Idempotency is check→execute→insert, not an atomic claim. Two equal concurrent requests can both execute the side effect; `ON CONFLICT` only drops the second cache write. | Duplicate tasks/files/other mutations during retries or races. | `IdempotencyFilter.java:68-104`; `IdempotencyRepository.java:46-52`. | Insert a PENDING claim before handler execution in one ownership protocol; add a two-thread integration test. Until then avoid parallel replay of one key. | M | P1 |
| Both outbox workers fetch `FOR UPDATE SKIP LOCKED`, but the scheduled fetch/send method is not transactionally claiming rows. | Multiple server replicas can deliver the same message/webhook. | `KwhOutboxWorker.java:47-52`, `KwhOutboxRepository.java:36-48`; `MsOutboxWorker.java:39-41`, `MsOutboxRepository.java:51-60`. | Atomically update rows to PROCESSING and return claimed rows; add stale-claim recovery. Enforce one server replica until verified. | M | P1 |
| Pool size is 20 with no measured workload baseline for 100 concurrent users. | Connection queueing or DB saturation cannot be predicted. | `application.yml:18`; target volume is recorded in `production-launch-checklist.md:31-33`. | Run k6/JMeter against representative data; tune from measured DB wait/latency, not assumptions. | M | P1 |
| Logs carry trace/client fields but are formatted text; no repository log aggregation/tracing bundle was found. | Cross-service incident diagnosis and PII masking are not proven operationally. | `application.yml:149`; `docs/ops/architecture-overview.md:101-104`; repository search found no OTel/Grafana/Prometheus bundle. | Define JSON schema, masking test and deployment-specific collector; preserve audit IDs. | M | P1 |

## Architecture decision

The chosen modular monolith and single-host Compose are proportionate for the
forecast 500 users / 100 concurrent users. HA or microservices are not release
requirements unless the approved SLO makes a single-host recovery model
unacceptable. Scaling horizontally before atomic idempotency/outbox claims is
unsafe.

