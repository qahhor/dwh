# Search and index maintenance

This runbook covers the first managed search-index implementation in a single
SmartupCMS installation. PostgreSQL is always the source of truth. Typesense
27.1 is a derived, rebuildable index and never authorizes a user. Browsers use
only the server API; the Typesense URL and API key are neither displayed nor
editable in Settings.

## Access and settings

The existing unrestricted-administrator check remains in force until scoped
row filtering is implemented. UI visibility is not an authorization boundary.

| Operation | Required server authorization |
|---|---|
| Search, status, job history, and preview with the saved policy | `platform.search.view` plus the unrestricted-administrator check |
| Read configuration or preview an unsaved policy | the preceding access plus `platform.settings.view` |
| Save, rebuild, retry, cancel, or rollback | `platform.settings.update` plus the unrestricted-administrator check |

This feature introduces no system role and does not delegate index access.
Projects and users are indexed only while `state=A`; tasks retain the existing
search projection rules.

The saved policy has these bounds:

- global result limit: integer `1–50`;
- interactive rate: `30–600` requests per minute;
- burst: `10–60`, and never greater than the per-minute budget;
- field weight: `0–127`, with at least one non-zero searchable field for each
  of task, project, and user;
- typo tolerance: `0–2`; prefix matching is enabled or disabled per field;
- schema profile: `MIXED` or `RU`.

`MIXED` preserves the current tokenizer. Selecting another configured profile
does not change the active generation; rebuild is required. Save uses an
optimistic version. HTTP `409` means another administrator changed the policy;
keep the local draft, refresh the server state, reconcile the values, and save
again. Preview can evaluate the unsaved policy without activating it.

Normal results identify `source=TYPESENSE` and `degraded=false`. A dependency or
registry failure can use the bounded PostgreSQL fallback with
`source=POSTGRES` and `degraded=true`. An exact `#ID` lookup is deliberately
resolved against PostgreSQL and is not evidence of index health.

## Runtime topology and delivery

Each server process starts one dedicated search-delivery thread. PostgreSQL
holds the durable ownership fence, generation registry, job state, projection
revision and per-generation delivery state, so only the current owner may
publish or switch a generation. Business writes commit their search revision
in the same PostgreSQL transaction; Typesense I/O happens after that transaction.

Failed deliveries retain only a fixed error code. Retry delay uses bounded
exponential backoff with jitter and a five-minute ceiling. Import HTTP success
is not enough: every JSONL acknowledgement is checked. Export/reconciliation is
streamed and bounded. Search logs must not contain source documents, snippets,
credentials or raw downstream error bodies.

During rollout, drain every old writer before starting the new search-aware
writer. An old application version does not publish the new revision records,
so app-only rollback to a pre-outbox writer cannot promise continuous indexing.
Use the release backup/restore and forward-migration policy; do not describe an
application-only rollback as preserving delivery.

## Upgrade and bootstrap

Run the supported one-shot `migrate` service before the server. Migration
`V026__search_index_management.sql` creates the registry, revisions, delivery,
jobs and settings state. Migration `V027__search_job_request_metadata.sql`
records the original target and retry lineage needed for safe replay. Do not
edit Flyway history or apply these statements manually.

On first start, if the three legacy collections (`tasks`, `projects`, `users`)
exist and the registry is empty, the worker registers that set as a legacy
active generation. Otherwise it allocates and verifies a new generation. In
either case, wait for initialized, healthy status before treating indexed
search as available.

## Capacity and cleanup policy

Before allocating a build, the server reads Typesense installation disk totals
and usage and compares free space with a conservative reserve: at least 64 MiB
and at least twice the bounded PostgreSQL projection-size estimate. Typesense
`/metrics.json` reports installation-wide disk values, not exact bytes for one
generation, so this guard is not a capacity guarantee.

At most four registered generations are permitted by default. Active, building,
retained and failed generations all consume a slot. A cancelled rebuild leaves
its generation failed and still consumes a slot; retry reuses that same
generation. There is no automatic collection cleanup in this release, and a
capacity conflict must never be worked around by weakening the cap or deleting
collections to rerun a test.

Manual cleanup requires an explicit operator review of the PostgreSQL registry,
job history, active generation, retained rollback targets and the exact three
collection names belonging to one non-active generation. Retain recovery
evidence and a database backup first. There is intentionally no generic delete
command here: never target an unknown collection, the active generation, or a
collection that is absent from the reviewed registry.

## Verify, rebuild and recover

Use Settings > Search, or the corresponding server endpoints, in this order:

1. Refresh status. Confirm Typesense is enabled and healthy, the registry is
   initialized, no mutating job is active, and registered generations remain
   below the cap before a rebuild.
2. Start **Verify** (`CHECK`). It reconciles the selected active generation
   against a frozen PostgreSQL projection and reports missing, extra,
   mismatched and pending counts without switching generations.
3. Start **Rebuild** only after the disk/cap guard passes. The active generation
   continues serving while a fresh candidate is discovered, delivered and
   reconciled. Activation is an atomic fenced switch; the previous active
   generation becomes retained.
4. **Cancel** is accepted only before activation. A completed, failed or
   activating job cannot be cancelled. A failed/cancelled job may be retried
   with a new request identity; the original attempt remains in history.
5. **Rollback** only to a listed retained generation with the supported schema.
   The worker first catches it up to current PostgreSQL revisions, performs a
   fresh verification, and then uses the same fenced switch. A historical
   verification alone never authorizes cutover.

HTTP `400` indicates an invalid request, `401` missing authentication, `403`
insufficient permission/unrestricted scope, `409` a settings version, job,
capacity or state conflict, and `503` unavailable/invalid settings, dependency
or storage metadata. HTTP `429` can come from the interactive Search budget or
the shared 10/minute Search-management family. Honor `Retry-After`; retry only
an idempotent read within a bounded observation deadline, and never
automatically retry a job-creation mutation. Job-level fixed codes include
`SEARCH_DEPENDENCY_FAILED`, `DELIVERY_RETRIES_EXHAUSTED`,
`RECONCILIATION_MISMATCH`, `INSUFFICIENT_SEARCH_STORAGE` and
`SEARCH_STORAGE_UNAVAILABLE`.

After a server restart, refresh status and job history. Ownership recovery
releases unfinished delivery claims and moves interrupted queued/running/
verifying/activating jobs back to a replayable state under the new fence. Do
not create a replacement job merely because the process restarted. Let the
durable job finish, retry only a terminal failed/cancelled attempt, and confirm
the active generation plus pending/failed counts before closing the incident.

For a retained-index recovery, keep the active generation serving, repair the
dependency, run Verify, and use Rollback only if the retained target is listed
and the new catch-up/verification succeeds. If both index generations are
unusable, PostgreSQL remains authoritative; restore the dependency and rebuild
rather than editing registry rows.

## Reproducible synthetic measurement

`e2e/scripts/search-benchmark.mjs` is for a fresh disposable loopback fixture,
never port 4200 or another protected QA stack. It reads only
`SEARCH_BENCHMARK_ORIGIN`, `SEARCH_BENCHMARK_LOGIN` and
`SEARCH_BENCHMARK_PASSWORD` from the process environment. The target must be a
bare credential-free loopback HTTP origin. Arguments are validated before the
first network request.

```powershell
$env:SEARCH_BENCHMARK_ORIGIN = 'http://127.0.0.1:14208'
$env:SEARCH_BENCHMARK_LOGIN = '<synthetic fixture login>'
$env:SEARCH_BENCHMARK_PASSWORD = '<synthetic fixture password>'
node e2e/scripts/search-benchmark.mjs `
  --tasks=300 --projects=30 --users=10 --concurrency=1 `
  --mode=current --output=C:/Temp/search-benchmark-current.json
```

Defaults are 300 tasks, 30 projects and 10 users; maxima are 3000, 300 and 100.
Concurrency is exactly 1 or 5. Each cold, warm and post-job-request window has
30 measured operations with rate-aware pacing. Cold means the first measured
batch after synthetic delivery readiness; it is not a flushed-cache experiment.
The fixed source covers Russian, Latin, both Uzbek apostrophe variants, typo,
prefix, exact ID and no-match queries. Output contains counts and query strings
but no record bodies or credentials. Latency percentiles use successful healthy
indexed matches only, with their actual sample counts; exact-ID fallback and
no-match operations remain in total/error/degraded accounting but are not
silently added to that percentile population.

The 30-operation post-job-request window describes timing after the REBUILD
request; it is not itself a rebuild-overlap population. A separate
job-lifetime-overlap result selects only request intervals that intersect the
job's server `createdAt..finishedAt` timestamps, clips them to that interval,
and reports the union so gaps are excluded and concurrent intervals are not
double counted. `createdAt..finishedAt` includes queue time and does not prove
active rebuild execution. This calculation assumes benchmark-host and server
epoch clocks are aligned. Missing/invalid timestamps or zero matching intervals
produce `measured=false` and null duration/percentiles; the broader 30-operation
window still retains every error, `429` and degraded response.

Every HTTP request has a 30-second transport cancellation bound. Search
readiness and delivery polling use at least 2.5 seconds (or the slower measured
rate-aware spacing), respecting the 30/minute default. Job observation uses a
10-second floor to leave room in the unchanged shared 10/minute management
family for settings, status and job creation. A job-observation `429` is counted
and its bounded `Retry-After` is honored; the REBUILD POST is never retried.
Concurrent job observation and the 30-operation phase are both settled under
owned failure handling before an error escapes, so a failed job cannot produce
a successful JSON result or leave unowned measurement requests.

Delivery reports three distinct monotonic timings: PATCH round-trip through the
HTTP `204`, request-start-to-indexed-marker observation, and
post-`204`-to-observation, plus poll count and resolution. The latter two are
visibility observations; none is exact database-commit lag. The JSON makes no
SLO or speedup verdict.

`--mode=baseline` issues three sequential entity requests per measured
operation. When it runs in a separate fresh fixture on the same current
immutable image, it is an emulated fan-out comparison, not a historical
pre-feature baseline. Actual old-source latency and before/after speedup remain
`UNMEASURED`; one request versus three is only a request-count observation. If
either run lacks equivalent fixed source and confirmed indexed matches, do not
publish a latency comparison.

A reduced 10-task/2-project/1-user, concurrency-1 run is a tiny bounded
functional/performance probe chosen for disposable-fixture and host-resource
isolation. It is not representative scale, an SLO verdict, or evidence for
production capacity. A zero-overlap outcome stays unmeasured; do not increase
data or load merely to manufacture overlap.

Record the exact source/working-tree hashes, image IDs, Typesense version,
dataset counts, concurrency, UTC interval, output path and any concurrent host
load with the measurement. Never seed from an existing installation or reuse a
fixture whose retained/failed/cancelled generations exhausted its capacity.
