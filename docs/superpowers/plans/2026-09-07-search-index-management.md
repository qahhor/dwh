# Search settings and index generation management implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give administrators real search controls, safe observable reindex/rollback jobs and verified end-to-end record search.

**Architecture:** Reuse the durable schema, projection builder, strict Typesense adapter and single writer from the reliability package. Persist typed instance settings; build separate physical generations and switch one PostgreSQL pointer after reconciliation. The Settings tab consumes permission-checked status/config/jobs endpoints and never accesses Typesense directly.

**Tech Stack:** Java 25/Spring Boot 4.1, PostgreSQL 18, Typesense 27.1, Angular 22, JUnit/Testcontainers, Vitest, Playwright, Micrometer.

**Spec:** `docs/superpowers/specs/2026-09-07-search-and-index-management-design.md` (approved 2026-09-07).

## Global Constraints

- PostgreSQL — источник истины; Typesense не авторизует пользователя.
- Сохраняются `platform.search.view` и текущая дополнительная unrestricted-admin проверка.
- Status/preview требуют `platform.search.view`; чтение configuration дополнительно требует `platform.settings.view`. Save/rebuild/retry/cancel/rollback требуют `platform.settings.update` и той же unrestricted-admin проверки.
- Новые системные роли и делегирование индексного доступа этим пакетом не вводятся.
- Browser работает только через server API.
- Остаются Java 25 / Spring Boot, PostgreSQL 18, Typesense 27.1 и Angular.
- Задачи индексируются согласно текущему составу; пользователи и проекты — только `state=A`.
- Автоматическое удаление старых collections в первом выпуске отключено.
- Операторский предохранитель по умолчанию допускает не более четырёх зарегистрированных поколений одновременно, включая active/building/retained/failed.
- Mixed language profile сохраняет current tokenization до отдельного принятого выбора RU.
- Значение global limit — целое 1–50.
- Search rate ограничивается 30–600/min, burst 10–60 и не больше per-minute budget.
- Weights в диапазоне 0–127, хотя бы один ненулевой searchable field на entity. Typo tolerance 0–2.
- Search API key и URL зависимости не показываются и не изменяются браузером.
- Сохранение имеет optimistic version: конфликт — 409, UI сохраняет введённые значения и предлагает обновить состояние.
- Сырые downstream error bodies и snippets в журнал не копируются.
- No push, deployment, production load, external provider changes or mutation of port 4200. Existing unrelated dirty changes and audit drafts remain outside commits.

## Prerequisites and task boundaries

Complete/review `2026-09-07-search-reliability.md` first. Its V026 tables, `SearchQueryPolicy`, `SearchPolicyProvider.current()`, `SearchChangePublisher`, `SearchProjectionReader`, `SearchDeliveryRepository`, `SearchIndexStateRepository`, `SearchWorkerCoordinator`, strict HTTP adapter and typed direct URLs are inputs. Read the actual completed interfaces before execution; record any precise compatible refinement in the SDD ledger rather than inventing a parallel abstraction.

Use the existing checkout and user's main/in-place preference. Stage task-only hunks in already-dirty files (catalogs, palette, handoff). No concurrent implementation agents. Per-task RED/GREEN, full reports in this plan's ignored SDD workspace and separate read-only review. Use `apply_patch` for authored files. Java: `C:/Tools/Java/jdk-25.0.2`; Maven: `D:/Claude/dwh/output/tools/maven/bin/mvn.cmd`; Node: `D:/Claude/dwh/output/tools/node-v24.15.0-win-x64`. Set JAVA_HOME/PATH in the command process only.

### Task 1: Typed settings, authorization, preview and observable status

**Files:**
- Create under `apps/server/src/main/java/com/greenwhite/dwh/instance/search/`: `controller/SearchManagementController.java`; `service/SearchSettingsService.java`, `SearchStatusService.java`, `SearchExecutionSnapshotReader.java`; `repository/SearchSettingsRepository.java`; `dto/SearchManagementDtos.java`.
- Modify `service/SearchPolicyProvider.java`, `SearchQueryPolicy.java`, `SearchService.java`, `SearchAccessPolicy.java`, `repository/SearchIndexStateRepository.java` and `typesense/TypesenseClient.java`.
- Create tests under `apps/server/src/test/java/com/greenwhite/dwh/instance/search/`: `SearchSettingsIntegrationTest.java`, `SearchManagementAuthorizationTest.java`, `SearchStatusTest.java`; extend HTTP/search/rate tests from package 1.

**Interfaces:**

```java
public record SettingsSnapshot(long version, SearchQueryPolicy policy) {}
public record SaveSettingsRequest(long version, SearchQueryPolicy policy) {}
public SettingsSnapshot current();
public SettingsSnapshot save(SaveSettingsRequest request);
// One DB statement snapshot joins active pointer/generation with saved policy.
public record SearchExecutionSnapshot(IndexSnapshot index, SettingsSnapshot settings) {}
public SearchExecutionSnapshot read();
```

Routes are under `/api/v1/search`: `GET /status`, `GET /settings`, `PUT /settings`, `POST /preview`. Preview body has `q`, `entity` and a validated optional policy draft; it neither saves settings nor changes schema and reports the active profile. Only users with config-read access may submit a draft; ordinary search-view admins may preview current settings. POST preview is counted in the search rate bucket before engine work. Controller annotations preserve permission checks and services also enforce admin policy so an internal call cannot bypass it.

- [ ] Write RED authorization matrix through the real Spring security/controller boundary: unauthenticated=401; search-only non-admin, settings-only non-admin and delegated settings writer=403; active admin with search.view can status/preview; missing settings.view blocks config; missing settings.update blocks save. Wildcard remains allowed. Invalid draft fails without HTTP calls. Do not grant permissions or introduce roles in a migration to make tests pass.
- [ ] Write real persistence RED: save/reload changes consumer output; stale version returns 409 without overwriting; invalid limits/unknown fields/duplicates/noninteger weights/all-zero weights/typos/profile are rejected; generic per-user `md_settings` cannot change search policy. Reject unknown JSON properties rather than silently persisting unused flags. Assert all settings have a consumer: emitted HTTP parameters, result cap, rate bucket or next-generation schema.

```java
var before = settings.current();
var saved = settings.save(new SaveSettingsRequest(before.version(), policyWithLimit(4)));
assertThat(saved.version()).isEqualTo(before.version()+1);
assertThat(provider.current().globalLimit()).isEqualTo(4);
assertThatThrownBy(() -> settings.save(new SaveSettingsRequest(before.version(), policyWithLimit(5))))
    .isInstanceOf(ApiException.class);
assertThat(provider.current().globalLimit()).isEqualTo(4);
```

- [ ] Implement immutable typed whitelist configuration using package-1 fields and defaults. All three entity lists must contain exactly the permitted field names once each; zero weights omit a field from `query_by`, and at least one remains. Only MIXED/RU profile is accepted. `globalLimit=10`, rate `120`, burst `20` defaults; exact per-field defaults from package 1 are retained. Update in a short transaction using `where version=:expected returning version`; one safe audit change in the same transaction. DB corruption is an explicit configuration error, not a silent fallback to permissive defaults. Empty migration seed `{}` decodes as defaults once, not a user-supplied raw object.
- [ ] Make `SearchPolicyProvider` read the saved singleton policy and version without per-user overrides; snapshot reads have no HTTP transaction. Avoid a DB connection on every rate-filter request by a bounded application snapshot cache, refreshed after successful local save and at a fixed short interval; failed refresh retains the last valid policy and exposes degraded settings status, never widens limits. Cache keys do not include users. Search execution reads active pointer + policy in one DB snapshot before engine access. Live bucket replacement must retain the package-1 debt invariant.
- [ ] Add sanitized Typesense health/debug/metrics/schema metadata adapters. Status separates dependency health, initialization, rebuild-required, queue lag/failed deliveries and last successful reconciliation. Return counts and timestamps, not full documents. Health failure yields nullable unknown index counts, not zero. Surface effective connect/read/fallback budgets and clamped cookie/API search rate; expose dependency version but not configured URL/key. Disk metrics from `/metrics.json` are installation-wide, not per-generation storage. Show per-generation document count and nullable storage size when not provided; do not label global disk usage as a generation's bytes.
- [ ] Query-weight/prefix/typo save applies to new queries. Profile save keeps active schema unchanged and sets rebuild-required until a matching-profile generation becomes active. Preview runs through the same access/validation/budget/fallback/mapper code as regular search; it cannot accept collection names, raw filters or arbitrary Typesense options. Safe structured error codes, no raw downstream body in JSON/audit/logs.
- [ ] Run focused settings/authorization/status/adapter/rate tests, full Maven verify and architecture gate. Commit `feat(search): expose validated instance search settings and status`.

### Task 2: Batch import, reconciliation and recoverable generation jobs

**Files:**
- Create under the search package: `service/SearchGenerationService.java`, `SearchJobService.java`, `SearchJobWorker.java`, `SearchReconciliationService.java`, `SearchStoragePreflight.java`, `SearchMetrics.java`; `repository/SearchJobRepository.java`, `SearchGenerationRepository.java`; `typesense/TypesenseDocumentStream.java`.
- Modify the existing `SearchDeliveryWorker`, `SearchWorkerCoordinator`, `SearchProjectionReader`, delivery/state repositories, collection schema, `TypesenseClient`, management DTO/controller and status service.
- Create tests under the search test package: `SearchJobIntegrationTest.java`, `SearchRebuildIntegrationTest.java`, `SearchRollbackIntegrationTest.java`, `SearchReconciliationTest.java`, `SearchImportHttpTest.java`, `SearchMetricsTest.java`.

**Interfaces:**

```java
public record StartJobRequest(UUID requestId, String action, UUID generationId) {}
public record JobReceipt(UUID id, String state) {}
public JobReceipt start(StartJobRequest request);
public JobReceipt cancel(UUID jobId);
public JobReceipt retry(UUID jobId, UUID requestId);
public void runOnce(); // actual single-coordinator job-worker cycle
// Stream bounded JSONL output, validate one acknowledgement per input in order.
public record ImportAck(String id, boolean success, String errorCode) {}
public List<ImportAck> importDocuments(String collection, List<Map<String,Object>> documents);
// Stream authoritative index metadata, never materialize entire export.
public void forEachDocumentMetadata(String collection, Consumer<DocumentMetadata> consumer);
public record DocumentMetadata(String id, long revision, String fingerprint, String contentFingerprint) {}
```

Routes: `POST /jobs` for CHECK/REBUILD/ROLLBACK, `GET /jobs?limit=20&cursor=...`, `GET /jobs/{id}`, `POST /jobs/{id}/cancel`, `POST /jobs/{id}/retry`. Mutations require search.view + settings.update + unrestricted admin; history/read uses search.view + unrestricted admin. Return 202 with ID for asynchronous work. Invalid action/request UUID or nonexistent generation is a structured 400/404; repeated identical requestId returns the existing job, different action/target with that ID is 409. Job history has bounded keyset pagination; no unrestricted `findAll`.

- [ ] HTTP RED: import sends `action=upsert` JSONL and checks every response line; `200` with one `success:false`, missing/extra lines or malformed line cannot acknowledge the whole batch. Preserve row alignment, expose only safe error codes, and cap each request at 100 documents/1 MiB encoded bytes. Oversized single document produces a visible `DOCUMENT_TOO_LARGE` failure, not OOM. For existing large fields use the same deterministic bounded projection policy for incremental/rebuild; never silently truncate only one path. Confirm schema metadata/streaming error paths in real loopback HTTP tests.
- [ ] Transaction/job RED on real PostgreSQL: idempotent start, concurrent start conflict, cancel accepted before ACTIVATING and rejected after, ownership invalidation after restart, resume the same job/cursor, terminal failure/retry, and four-generation cap rejecting a fifth without deleting the active one. CHECK is nonmutating to the index. Failed build generations count toward the cap; retry reuses its generation. Startup initial builds use the same job lifecycle, not a separate unobservable implementation.
- [ ] Write rebuild RED using a disposable Typesense 27.1 + PostgreSQL fixture: seed stale extra hit and missing hit; build a new generation; inject a source update during discovery/import and a deletion while the candidate is catching up. Assert final ID sets, fields/revisions/fingerprints, active search and preserved old collections. Add rollback RED that deletes/excludes a record after retaining the old generation; rollback must catch up and not resurrect it. Do not claim mocked import as real engine evidence.
- [ ] Implement server-generated physical names `cms_<uuidWithoutDashes>_tasks/projects/users`, frozen schema/profile/settings snapshot, and keyset discovery through the shared reader/revision queue. Generation name components never come from user strings. Existing legacy names remain registered until verified cutover. Rebuild never clears active index. Add metadata fields and RU locale/stem only on intended text fields in a new RU generation; keep login/email/phone exact-identifier tokenization. Collection creation only follows verified absence, not every HTTP error.
- [ ] Alternate bounded active-delivery and job work on the one coordinator so a long rebuild does not starve current updates. Batch per collection with per-row acks and existing durable claim/version semantics. Failures remain persisted with bounded retry and safe error summary. Worker shutdown stops new claims and safely finishes/cancels in-flight transport; recovery creates no second writer. Persist progress after each bounded unit, not only at job end.
- [ ] Implement reconciliation using schema checks, streamed ID/revision/fingerprint metadata and keyset source projections. The stream recomputes `contentFingerprint` from the actual indexed searchable fields using the same canonical projection hashing, rather than trusting the stored fingerprint alone. Test a changed indexed body with unchanged stored metadata as a mismatch. Use a bounded temporary PostgreSQL comparison table or scoped on-disk temporary storage if complete set comparison requires external memory; do not load every ID/document into heap. Clean only the task-owned temporary object. Report aggregate missing/extra/mismatched/pending counts. A source revision newer than delivered is pending and must be caught up/rechecked, not a successful content match. Legacy missing metadata always needs rebuild. CHECK stores reconciliation summaries; it never inserts/deletes Typesense docs.
- [ ] Before cutover, acquire singleton state `FOR UPDATE`, recheck owner/job state/frozen profile, completed discovery and all current required revisions delivered. The publisher's `FOR SHARE` barrier prevents a commit from escaping this final check. Recheck verification freshness against the revision set; if changed, return to catch-up/verification. Atomically set new active pointer + increment version, retain old generation, and complete job/audit in the same DB transaction. No three-alias pseudo-transaction. Searches snapshot one generation; an already-started old query can complete safely because collections remain.

```sql
-- Inside the short activation transaction after ownership and candidate checks:
update search_index_state
set active_generation_id=:candidate, version=version+1, initialized=true
where id=1 and version=:expected;
-- Require exactly one affected row, otherwise abort/retry the activation decision.
```

Rollback follows the same catch-up/reconcile/barrier/CAS for an existing RETAINED generation. Refuse a legacy/unverifiable generation with a safe explanation; do not claim its missing metadata as verified. Schema saved mid-build does not rewrite frozen candidate; on success compare active profile against current settings and retain rebuild-required where different. A cancelled/failed generation remains registered and never silently disappears.
- [ ] Implement storage preflight before allocating a new generation: read total/used bytes from Typesense metrics, reject unhealthy or unmeasurable space with an explicit safe reason, reserve at least max(64 MiB, twice a bounded source serialized-size estimate) in addition to current used space. This is a conservative guard, not a capacity guarantee; refresh during build and stop without touching active collections on exhaustion. Expose unknown per-generation bytes as unknown. Keep operator cap configurable via server deployment properties with safe minimum/default 4, not arbitrary browser raw parameters. Provide no auto-cleanup endpoint in this release.
- [ ] Audit settings/start/cancel/retry/rollback/switch using existing audit storage. Carry actor explicitly into worker-generated audit rows without forging a request SecurityContext. Summary contains action/job/generation/config versions, no document content. Record query duration/errors/fallback/429, engine time, pending/lag/retries, import row success/failure, job duration/state and switch counters via finite labels only. Meter tests assert label sets exclude query/user/job UUID cardinality; instrument actual paths, not unused counters.
- [ ] Run focused PostgreSQL/Typesense/HTTP tests, full Maven verify and architecture gate. Run Graphify update (unstaged). Commit `feat(search): rebuild and roll back verified index generations`.

### Task 3: Settings search tab and operational UI

**Files:**
- Create `apps/web/src/app/features/settings/search/search-settings.component.ts`, `.html`, `.scss`, `.spec.ts`.
- Create `apps/web/src/app/core/models/search-management.models.ts`, `core/services/search-management.service.ts`, `.spec.ts`.
- Modify `features/settings/settings.component.ts` and `.spec.ts` only for tab integration.
- Extend RU source catalog and sync packaged fallback with task-only hunks.
- Create `e2e/tests/browser/instance/search-management.spec.ts`.

**Interfaces:** Typed DTOs mirror Task 1/2 responses: settings `{version,policy}`, status with health/readiness/counts/lag/effective budgets/storage/last-check, jobs with ID/action/generation/state/progress/safe error/timestamps and bounded history cursor. Service methods `status`, `settings`, `save`, `preview`, `startJob`, `job`, `jobs`, `cancel`, `retry` use server API only, local-error ownership and cancellation of replaced reads.

- [ ] Use frontend testing/debugging skill. RED compiled-component tests with actual template for missing permissions, read-only status, invalid save preventing request, pending save/one request, error preserving draft, 409 preserving draft with explicit reload, a successful response becoming saved baseline and schema change showing rebuild-required. A dirty draft must not be replaced by background status polling.

```typescript
const saveButton = fixture.nativeElement.querySelector('button[data-action="save-search-settings"]');
saveButton.click(); saveButton.click();
expect(http.match(req => req.method==='PUT' && req.url.endsWith('/search/settings')).length).toBe(1);
expect(saveButton.disabled).toBe(true);
// Flush 409: the entered limit remains; a reload action is offered, no silent overwrite.
```

- [ ] Build five blocks within a separate tab: status, relevance, language profile, protection, maintenance. Use existing design tokens/controls rather than introducing a new design system. Editable values are exactly those enforced/consumed by backend; effective transport/query budgets are read-only. Explain MIXED/RU requiring a rebuild, no immediate active schema change, and unknown statistics honestly. Engine key/URL/raw parameters do not appear in DOM or DTO.
- [ ] Category field rows have bounded integer weight/typo and prefix controls with labels. Preview uses the unsaved validated query policy with a visible active-language-profile label; no save side effect. Preserve result cancellation and escape all snippet text. Only server-authorized actions display after status/permission loading; unknown permissions do not optimistically enable buttons.
- [ ] Add confirm dialogs describing rebuild retention/disk guard and rollback catch-up; cancel does not promise interruption after ACTIVATING. Generate one requestId per user action and preserve it across an uncertain network retry. On 202 poll that job with lifecycle-bound subscription; no overlapping polls and no polling after tab destruction. Leaving the page never cancels server work. Disable conflicting mutations, retain job ID on network errors, offer one manual retry. Completed/failed/cancelled status stops rapid polling and refreshes status/history once.
- [ ] Expose no delete-collection button. At capacity show the operator-required cleanup message and retained generation list with known/unknown storage values. Rollback target comes only from returned eligible generations, not a text input. Job errors use localized safe-code mapping with generic fallback.
- [ ] Add keyboard focus, aria-busy/live region, touch targets and local scrolling for field/history grids. Verify light/dark at 320/390/1366 without page overflow. Add browser cases for real save/preview/check/rebuild/current-generation/status and controlled 503/409/late responses; label mocked paths separately. Screenshots/credentials remain outside Git.
- [ ] Run focused/all Angular tests, app/E2E typecheck, i18n sync/audit and production build; defer combined runtime execution to Task 4 with explicit report entry. Commit task-only changes as `feat(search): add index status and maintenance settings tab`.

### Task 4: Combined isolated acceptance, measurements and operator documentation

**Files:**
- Extend permanent `search-reliability.spec.ts` and `search-management.spec.ts` from the previous tasks only where full integration exposes genuine gaps.
- Create `e2e/scripts/search-benchmark.mjs` and `e2e/tests/config/search-benchmark.test.mjs` for bounded reproducible synthetic measurements.
- Create `docs/ops/search-and-index-maintenance.md`; link it from `docs/README.md` and `docs/ops/maintenance-guide.md`.
- Update the agreed search sections/evidence paths in `docs/technical-specification.md` to reflect implemented module boundaries without expanding admin-only policy; update `docs/ai-context.md` with task-only current handoff.

**Interfaces:** Benchmark requires an explicit loopback origin and scoped test credential from environment; it must reject 4200 and non-loopback targets. Dataset seed/counts and query mix are printed without record bodies/credentials. Output JSON contains warm/cold setup, concurrency, counts, p50/p95/p99, errors/429 and rebuild overlap duration; no invented SLO verdict.

- [ ] Add benchmark argument RED: production/non-loopback/4200 target rejected before network, invalid counts/concurrency rejected; missing credentials give no value leak. Implement bounds default 300 tasks/30 projects/10 users, max 3000/300/100, concurrency 1 or 5, 30 measured requests per scenario with rate-aware pacing. Inputs/runs may be reduced to a smaller explicit fixture on constrained hardware; always report actual counts. Use a fixed synthetic seed and strings covering Russian, Latin, Uzbek apostrophe variants, typo, prefix, exact ID and no-match.
- [ ] Build an isolated loopback-only candidate from the exact feature source and preserved CMS working content with dedicated Compose project/data volumes, migrated schema 026, matching server/web images and Typesense 27.1. Do not restart/reconfigure current 4200 or unrelated QA stacks. Capture source hashes/image IDs and migration result without secret values. Use existing secure fixture/bootstrap patterns. Do not read user data into this dataset.
- [ ] Run full Maven verify, full Angular tests, app build/typecheck, i18n audit, E2E typecheck/config/artifact-security and all instance Playwright cases, including direct-record reload/back, permission denial, real settings consumer, update-after-commit, failed import, stale removal, build overlap, rollback exclusion and restart recovery. Backend real-container integration tests carry cases unsafe/unreliable to orchestrate through a browser. Do not substitute screenshots for persisted-data assertions.
- [ ] Inspect UI visually in light/dark at 320/390/1366, inspect browser console/runtime errors and perform keyboard actions. Use accessibility checks on search/settings. Fix only concrete in-scope failures through RED/GREEN and one reviewed fix batch. Preserve unrelated known issues and report them if they affect broader acceptance.
- [ ] Compare baseline/current on identical synthetic source data in separate isolated runs. Record baseline request count (three sequential requests) and missing-index caveat; evaluate latency only on matched indexed records. Record one-request multi-search, healthy/degraded results, active search while rebuilding and measured delivery lag. If a before/after run is unavailable, label it unmeasured and report only measured current figures; never infer speedup from request count alone. Do not add GIN indexes absent comparative query-plan/load evidence.
- [ ] Document settings semantics, permissions, retries/failure codes, one-writer topology, upgrade migration, legacy bootstrap, conservative disk/cap guard, no automatic cleanup, verify/rebuild/cancel/retry/rollback procedure and retained-index recovery. Explain old-writer rollout must be drained to prevent missing publisher revisions; do not claim app-only rollback to pre-outbox writer preserves continuous indexing. Explain a failed/cancelled generation consumes a slot and manual collection/registry cleanup requires explicit operator review; no automatic delete recipe that targets unknown collections. Include verified tests and honest performance figures, not audit drafts.
- [ ] Run all architecture/docs/hygiene/release/config gates from the testing strategy, `git diff --check`, and Graphify AST update. Generated graph remains unstaged because the checkout includes unrelated unpublished files. Commit only authored feature docs/tests and task-specific handoff hunks.
- [ ] Request final whole-feature review using the feature baseline, both plans/spec and their reports. Resolve Critical/Important findings through the prescribed fix/re-review workflow. End with what works, exact verification, measured limits and remaining scope exclusions; no push/deploy/readiness claim for 4200 or production.

## Verified dependency references

Typesense 27.1 supports one request containing ordered searches ([multi-search](https://typesense.org/docs/27.1/api/federated-multi-search.html)). JSONL import needs per-line acknowledgement checks, including on HTTP 200, and export supports streaming ([documents](https://typesense.org/docs/27.1/api/documents.html)). `GET /metrics.json` exposes installation disk totals/usage, not a promise of exact per-generation bytes ([cluster operations](https://typesense.org/docs/27.1/api/cluster-operations.html)). Query fields, weights, typo and prefix parameters must use the version-specific API ([search](https://typesense.org/docs/27.1/api/search.html)). Keep this dependency version; no upgrade is part of the plan.
