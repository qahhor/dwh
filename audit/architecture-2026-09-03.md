# Architecture audit — 2026-09-03

Scope: SmartupCMS `main` at `df84019886aa47c504e5562253dd1ef842b3d96b`.
Detailed command evidence and limitations are in
[evidence/cto-audit-2026-09-03.md](evidence/cto-audit-2026-09-03.md).

## System map

SmartupCMS is an open-source, single-organization system per installation. Its
supported production shape is an Angular 22 web origin in front of one Spring
Boot 4.1.1 / Java 25 modular monolith, PostgreSQL 18 as source of truth,
Typesense 27.1 as derived search, and local/S3-compatible object storage
(`README.md:14-38`, `pom.xml:16-40`). Managed installations intend to use
Hetzner, Cloudflare and R2; no applied target configuration is present in this
repository.

Business modules are auth/session/token, IAM/RBAC/master data, projects/tasks,
comments, files, notifications/announcements, audit, webhooks, analytics,
reports, search and system status. External trust boundaries are SMTP/SMS/
messenger providers, outbound webhooks, Typesense and S3-compatible storage.
No payment integration or message broker was found.

```text
Browser -> Cloudflare (planned, not evidenced) -> Angular/Nginx -> Spring API
                                                        |-> PostgreSQL
                                                        |-> Typesense
                                                        |-> local disk or S3/R2
                                                        |-> SMTP/SMS/messenger/webhooks
Backup job --------------------------------------------> PostgreSQL + off-host target
```

## Architectural decision

**Retain the modular monolith.** At 500 named users, a conservative acceptance
target of 100 concurrent active users and 50 GB/month uploads, microservice
decomposition would add deployment, consistency, tracing and on-call cost before
there is evidence of a scaling boundary. Scale vertically and optimize measured
bottlenecks first. Extract a service only after a module has an independently
measured scaling/reliability need and a stable contract.

DDD is present as domain-oriented packages and vocabulary, but not as strict
aggregate isolation: services directly orchestrate JDBC repositories and
cross-module records. That is acceptable for this product stage. SOLID is
partially enforced by controller/service/repository separation and provider
SPIs; oversized UI components and infrastructure-heavy service methods are the
main exceptions.

## Findings

| Observation | Risk | Evidence | Minimal action / acceptance criterion | Effort | Priority |
|---|---|---|---|---|---|
| Top-level packages are cycle-free and controller→service→repository rules are executable. | Architectural regression is constrained; current modularity is sufficient. | `apps/server/src/test/java/com/greenwhite/dwh/instance/architecture/ModularArchitectureTest.java:39-109`. | Keep ArchUnit required. Add a rule only when a real forbidden dependency appears; do not create microservices. | S | P2 |
| Upload orchestration mixes external storage, scanner, copying, quota and persistence in one transactional service method. | DB connections remain held during slow/unavailable external I/O; transaction boundaries do not match failure boundaries. | `MfFileService.java:43-150`; Hikari max 20 in `application.yml:18`. | Split into bounded staging/scan, short atomic DB reservation/finalization, compensating cleanup. Prove 20 parallel slow uploads do not starve normal API. | M | P0 |
| File quota is check-then-insert and physical object lifetime is inferred from metadata after deletion. | Concurrent uploads can exceed quota; upload/delete races can leave metadata pointing to a removed object. | `MfFileService.java:53-68,94-115,181-204`; `MfFileRepository.java:98-140`. | Add atomic quota reservation and per-hash serialization or a physical-object/refcount table. Add two-thread upload/delete integration tests. | M | P1 |
| Idempotency now claims a PENDING row, but a crashed owner has no explicit lease/reclaim protocol; cleanup has no discovered scheduler. | A request key may remain permanently “in progress”; table can grow without bound. | `IdempotencyService.java:38-76`; `IdempotencyRepository.java:34-101`; only service declaration found for `cleanupOldKeys`. | Store reservation expiry/heartbeat, reclaim stale owners, schedule retention cleanup, and test crash after domain commit. | M | P1 |
| Notification/webhook outboxes atomically claim PROCESSING rows with claim tokens and stale-claim recovery. | Duplicate delivery is reduced; exactly-once is still not promised across remote timeout ambiguity. | `MsOutboxRepository.java:52-118`; `KwhOutboxRepository.java:38-115`. | Preserve at-least-once semantics; document consumer idempotency and instrument retries/dead letters. | S | P2 |
| Rate limiting and SSE subscribers are process-local. Deadline reminder check→send is not atomically claimed. | A second server replica changes rate limits, loses cross-node SSE delivery and can duplicate reminders. | `RateLimitService.java:11-26`; `MsSseRegistry.java:12-29`; `TaskDeadlineReminderWorker.java:25-76`. | Declare `server replicas=1` release invariant. If scale-out becomes necessary, move these states/claims to shared storage before adding replicas. | M | P1 |
| Read authorization is permission-based and current ADR explicitly makes task visibility organization-wide/default `ALL`; inspected task/file read paths have no object-level owner/member predicate. A separate file-read policy was not found, while the threat model claims scoped checks. | Conditional IDOR/data-overexposure risk only if launch requirements expect project/member/owner isolation; otherwise documentation/test inconsistency. | `docs/adr/ADR-0013-data-scope.md:16-23,130-132`; `MsTaskController.java:136-178`; `MfFileController.java:75-85`; `docs/security/threat-model.md:40`. | Product explicitly accepts org-wide task/file reads or approves narrower scope. Encode any narrower rule server-side and add direct-API cross-role tests. | M | P0 conditional / P1 documentation |
| Several accepted ADRs still describe Control Plane/Nomad/Vault fleet architecture although supported runtime is unified single-installation. | New contributors and operators can implement against contradictory architecture. | `docs/adr/ADR-0009*`, `ADR-0010*`, `ADR-0011*`; current model `README.md:14`, `docs/ops/architecture-overview.md:7`. | Mark obsolete ADRs superseded/partially superseded and add one current deployment/resilience ADR. Preserve history. | S | P1 |

## Scaling model

1. **Release:** one server replica, one PostgreSQL primary, one Typesense node,
   external R2/local storage; vertical headroom and tested restore.
2. **First response to growth:** measure and resize CPU/RAM/IOPS/pool; add SQL
   indexes proven by `EXPLAIN (ANALYZE, BUFFERS)`; keep stateless web scalable.
3. **Only after measured saturation:** PostgreSQL read/HA strategy, shared rate
   limiting/event fan-out, atomic scheduled-work claims, then multiple server
   replicas behind the existing origin.
4. **Fleet scalability:** automate 100 independent installations with a separate
   ops/IaC layer and inventory. Do not reintroduce a product Control Panel.

## Architecture success metrics

- ArchUnit and module tests remain green on every PR.
- No storage/network call occurs inside a database transaction in the upload
  path; DB connection hold p95 for upload finalization <250 ms under target load.
- Zero duplicate reminder in a two-worker race test; idempotency PENDING age has
  an alert and converges below its lease threshold.
- Every object read/update/delete endpoint has a documented data-scope rule and
  at least one negative cross-role test.
- A second app replica is prohibited by deployment validation until shared-state
  prerequisites are complete.
