# Performance and scalability audit — 2026-09-03

No representative production dataset, APM trace, SQL statistics or completed
load test was found. Findings below are code-level risks; they are not claims
about measured production latency. Target assumptions from the user are 500
users, 100 concurrent active users and 50 GB/month uploads. Whether those values
are per installation or across the managed fleet remains unconfirmed; acceptance
testing should conservatively use them per installation.

## Critical flow analysis

| Observation | Risk | Evidence | Minimal action / acceptance criterion | Effort | Priority |
|---|---|---|---|---|---|
| Upload keeps a DB transaction open across object upload, AV scan, download/re-upload copy and metadata persistence. Scanner read timeout is 60s; pool max is 20. | 20 slow uploads can exhaust the pool and increase latency/errors for login/tasks/health. | `MfFileService.java:43-150`; `application.yml:18,107-114`. | Remove external I/O from the DB transaction; run 20 parallel 49 MB uploads plus normal API traffic and keep pool pending=0 sustained, API p95 within SLO. | M | P0 |
| S3 upload stages the full object locally to hash it; quarantine→final copy streams down and uploads again. Container `/tmp` is tmpfs. | Extra disk/network passes and RAM-backed temporary space create OOM/latency risk during concurrent uploads. | `S3StorageProvider.java:52-113`; `MfFileService.java:143-150`; `docker-compose.prod.yml:44-49`. | Use bounded disk-backed staging with quota/alert; use provider-side copy where supported; measure peak temp bytes and upload p95. | M | P1 |
| Auth filter reads session/token, user, effective permissions/version and updates last-seen on each authenticated request. | DB write amplification and permission-query load grow with request rate, not user count. | `KauthAuthenticationFilter.java:55-99`; `KauthSessionRepository.java:44-72`; `MdPermissionRepository.java:97-113`. | Instrument query count; throttle last-seen to e.g. once/5 min and cache/collapse principal snapshot keyed by permission version while preserving immediate revocation. | M | P1 |
| PostgreSQL fallback search uses leading-wildcard `ILIKE` across users/tasks/projects; `pg_trgm` is enabled but no `gin_trgm_ops` migration was found. | Sequential scans as data grows or when Typesense is unavailable. | `SearchService.java:68-133`; `V001__baseline.sql:11`; no trigram index in `db/migration`. | Capture three representative EXPLAIN plans, then add only proven trigram indexes; acceptance: no sequential scan above approved row threshold and search p95 meets SLO. | S/M | P1 |
| File listing uses filename `ILIKE`, orders by `created_at DESC`, and only accepts a limit, not a cursor. | Deep/growing lists become expensive and cannot paginate deterministically. | `MfFileRepository.java:159-199`. | Add keyset cursor `(created_at,id)` and supporting index; add trigram index only if measured search requires it. | M | P1 |
| Analytics aggregates broad task/member tables on demand; no materialization/cache or representative EXPLAIN evidence exists. | Dashboard can contend with transactional traffic as task history grows. | `AnalyticsRepository.java:21-168`; `AnalyticsController.java:27-49`. | Benchmark with 12–24 months synthetic data; set query timeout; add selective indexes or short TTL cache/materialized aggregate only where plans exceed budget. | M | P1 |
| Typesense startup sync loads full record lists and indexes documents one-by-one; enabled on startup. | Startup amplification, memory spike and duplicate rebuilds on multiple nodes. | `TypesenseSyncRunner.java:43-124`; `application.yml:153`. | Convert to cursor/batches and an explicit/leader-triggered rebuild; track documents/sec, errors and lag. | M | P2 before large datasets |
| No shared application cache is present. | Adding Redis prematurely would increase cost/operations; some hot reads may still need local bounded caching. | No Redis dependency/service found; permissions are materialized in PostgreSQL. | Do not add a distributed cache for release. First cache immutable/config reads in-process with version invalidation; introduce shared cache only from trace evidence. | S | P2 |

## Load and capacity test design

Minimum staging dataset: 500 active users, representative permission sets, at
least 100k tasks/comments, 100k file metadata rows, 12 months of audit records,
and R2/local objects including 1 MB, 10 MB and 49 MB files. Synthetic data must
contain no real PII.

Scenarios:

1. **Interactive mix, 100 virtual users, 60 minutes:** login/session refresh,
   task list/detail/update, comments, files list, notifications and search.
2. **Upload contention:** 20 concurrent 49 MB uploads plus 80 interactive users;
   scanner and R2 latency injected at p95 target.
3. **Degraded dependencies:** Typesense unavailable, webhook/provider timeout,
   scanner unavailable; verify fallback, fail-closed behavior and pool isolation.
4. **Soak:** 4 hours before RC and 24 hours on the final target, with stable
   heap, connections, temp space, outbox lag and error rate.

Proposed release SLOs require Product/Operations approval; they are targets, not
current facts:

| Metric | Proposed gate |
|---|---|
| API availability excluding planned maintenance | >=99.9% monthly |
| Interactive API p95 / p99 | <=500 ms / <=1.5 s |
| Search p95 with Typesense healthy | <=750 ms |
| 49 MB upload completion | p95 <=60 s on target network; no API pool starvation |
| HTTP 5xx under target test | <0.5%; zero sustained pool timeout |
| DB pool | pending connections 0 sustained; utilization <80% p95 |
| JVM | no OOM; post-GC heap stable over soak |
| Outbox | p95 delivery lag <=60 s; dead letter alert <=5 min |
| Restore | RPO/RTO explicitly approved after measured drill |

## Optimization order

1. Fix upload transaction/ingress and measure.
2. Establish SLO dashboards and query/connection metrics.
3. Run representative load; tune JVM/DB pool/host from evidence.
4. Optimize auth write amplification and proven slow SQL.
5. Add indexes/keyset pagination.
6. Consider cache/replicas only after a measured residual bottleneck.

