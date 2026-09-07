# Reliable record search implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make committed CMS records reliably searchable, expose dependency failures honestly, and open the selected record.

**Architecture:** PostgreSQL owns authoritative data and coalesced projection revisions. A single bounded background writer delivers committed projections to Typesense; request threads use one multi-search or an explicitly marked SQL fallback. This first package provides the durable substrate used by the subsequent index-management plan.

**Tech Stack:** Java 25, Spring Boot 4.1, PostgreSQL 18, Typesense 27.1, Angular 22, JUnit/Testcontainers, Vitest and Playwright.

**Spec:** `docs/superpowers/specs/2026-09-07-search-and-index-management-design.md` (approved 2026-09-07).

## Global Constraints

- PostgreSQL — источник истины; Typesense не авторизует пользователя.
- Сохраняются `platform.search.view` и текущая дополнительная unrestricted-admin проверка.
- Browser работает только через server API.
- Остаются Java 25 / Spring Boot, PostgreSQL 18, Typesense 27.1 и Angular.
- Три категории поиска сохраняются: TASK, PROJECT, USER.
- Задачи индексируются согласно текущему составу; пользователи и проекты — только `state=A`.
- Применённые Flyway migrations неизменяемы. Добавляется следующая свободная версия, ожидаемо V026; перед реализацией номер сверяется с checkout.
- Старые несвязанные изменения и локальные audit-черновики не включаются в этот пакет.
- Debounce 120 ms и отмена старых результатов сохраняются.
- Query обрезается по краям, длина 2–200 символов; entity — ALL/TASK/PROJECT/USER.
- Старые collections не удаляются во время переключения.
- Standalone topology only; no Redis/Kafka, Typesense upgrade, OCR/RAG, new roles, push, deployment or mutation of the installation on port 4200.

## Execution and evidence

Continue in the existing checkout according to the user's established main/in-place preference. Preserve dirty CMS changes. Before a task touches a dirty file, record its starting diff; stage only the task delta, never an unrelated whole file. In particular, Russian catalogs, the command palette and `docs/ai-context.md` already contain earlier CMS work. Do not stage `audit/`, ignore files or Graphify output.

Use `apply_patch` for authored files. Each implementation task has a separate reviewer, with no concurrent implementers. Follow the task's RED/GREEN steps, record actual output in the plan's ignored SDD report, then commit explicit paths/task hunks. A failed assertion is RED evidence; missing dependencies and compilation mistakes are not.

PowerShell command prefix from repository root:

```powershell
$env:JAVA_HOME='C:/Tools/Java/jdk-25.0.2'
$env:PATH='D:/Claude/dwh/output/tools/node-v24.15.0-win-x64;' + $env:PATH
& D:/Claude/dwh/output/tools/maven/bin/mvn.cmd -B -q -pl apps/server -am '-Dtest=SearchServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Use the actual test names listed in each task in place of `SearchServiceTest`. Existing Java/JNA warnings must be reported, not represented as clean output. Never log generated test passwords, HTTP credentials, `.env`, query text or downstream document bodies. Full evidence belongs in external QA/ignored reports; committed documentation contains reproducible commands and verified summaries only.

### Task 1: Durable search schema and migration safety

**Files:**
- Create `apps/server/src/main/resources/db/migration/V026__search_index_management.sql`.
- Create `apps/server/src/test/java/com/greenwhite/dwh/instance/db/SearchIndexManagementMigrationTest.java`.
- Modify `apps/server/src/test/java/com/greenwhite/dwh/instance/db/FlywayMigrationValidationTest.java`, `FlywayMigrationScriptIntegrityTest.java`, and `MigrationGateAndBootstrapTest.java` only where existing assertions require the latest version/count.

**Interfaces:** Produces six additive tables below. No production business row is altered or backfilled by this migration. Later tasks add Java consumers, not a second incompatible schema.

- [ ] Write migration tests first using real PostgreSQL 18. Reuse the existing `AuthenticationGenerationMigrationTest` setup pattern, target `025`, insert one task/project/user and one audit row, then migrate to latest. Assert immutable business snapshots, readiness rejection at 025, acceptance at latest, repeat `migrationsExecuted=0`, and empty-database success. Before adding SQL, obtain an assertion failure for missing new tables with a catalog query (not a relation-not-found error).

```java
assertThat(jdbc.sql("""
    select count(*) from information_schema.tables
    where table_schema='public' and table_name in
    ('search_projection_versions','search_generations','search_generation_delivery',
     'search_index_state','search_jobs','search_settings')
    """).query(Long.class).single()).isEqualTo(6L);
```

- [ ] Run `SearchIndexManagementMigrationTest` and capture expected RED.
- [ ] Add the expand-only migration. The exact data contract is:

```sql
create table search_projection_versions (
  entity_type text not null check (entity_type in ('TASK','PROJECT','USER')),
  entity_id bigint not null check (entity_id > 0),
  revision bigint not null check (revision > 0),
  changed_at timestamptz not null default clock_timestamp(),
  primary key (entity_type, entity_id)
);
create table search_generations (
  id uuid primary key,
  state text not null check (state in ('LEGACY','BUILDING','ACTIVE','RETAINED','FAILED')),
  task_collection text not null unique,
  project_collection text not null unique,
  user_collection text not null unique,
  schema_version integer not null check (schema_version >= 0),
  schema_profile text not null check (schema_profile in ('MIXED','RU')),
  settings_version bigint not null check (settings_version > 0),
  discovery_entity text check (discovery_entity in ('TASK','PROJECT','USER','DONE')),
  discovery_after_id bigint not null default 0 check (discovery_after_id >= 0),
  verified_at timestamptz,
  created_at timestamptz not null default clock_timestamp()
);
create table search_generation_delivery (
  generation_id uuid not null references search_generations(id),
  entity_type text not null,
  entity_id bigint not null,
  delivered_revision bigint not null default 0 check (delivered_revision >= 0),
  attempted_revision bigint not null default 0 check (attempted_revision >= 0),
  attempts integer not null default 0 check (attempts >= 0),
  next_attempt_at timestamptz not null default clock_timestamp(),
  error_code text,
  owner_token uuid,
  delivered_fingerprint text,
  primary key (generation_id, entity_type, entity_id),
  foreign key (entity_type,entity_id) references search_projection_versions(entity_type,entity_id)
);
create table search_index_state (
  id integer primary key check (id=1),
  active_generation_id uuid references search_generations(id),
  version bigint not null default 1 check (version > 0),
  initialized boolean not null default false,
  worker_owner uuid,
  worker_started_at timestamptz
);
insert into search_index_state(id) values(1);
create table search_jobs (
  id uuid primary key,
  request_id uuid not null unique,
  action text not null check (action in ('CHECK','REBUILD','ROLLBACK')),
  generation_id uuid references search_generations(id),
  state text not null check (state in ('QUEUED','RUNNING','VERIFYING','ACTIVATING','SUCCEEDED','FAILED','CANCELLED')),
  actor_id bigint,
  owner_token uuid,
  processed_count bigint not null default 0 check (processed_count >= 0),
  failed_count bigint not null default 0 check (failed_count >= 0),
  verification jsonb,
  error_code text,
  created_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp(),
  finished_at timestamptz
);
create unique index search_one_mutating_job on search_jobs ((1))
where action in ('REBUILD','ROLLBACK') and state in ('QUEUED','RUNNING','VERIFYING','ACTIVATING');
create index search_delivery_retry on search_generation_delivery(generation_id,next_attempt_at);
create index search_jobs_recent on search_jobs(created_at desc,id);
create table search_settings (
  id integer primary key check (id=1),
  version bigint not null default 1 check (version > 0),
  configuration jsonb not null default '{}'::jsonb check (jsonb_typeof(configuration)='object'),
  updated_by bigint,
  updated_at timestamptz not null default clock_timestamp()
);
insert into search_settings(id) values(1);
```

Collection names are generated and validated by Java, never accepted from a request. `verification` stores aggregate counts and safe mismatch summaries, not document bodies or query snippets. Actor IDs deliberately have no cascading user FK: audit/job history survives anonymization. No seed declares an index ready. Existing empty configuration means the documented defaults, not an unrestricted raw parameter map.

- [ ] Add real constraint cases: reject invalid entity/revision and duplicate concurrent mutating jobs; permit completed job history and a CHECK alongside delivery. Run focused migration/gate tests, then full Maven verify.
- [ ] Self-review snapshots, run `git diff --check`, commit only migration/tests as `feat(search): add durable index management schema`.

### Task 2: Strict multi-search, bounded SQL fallback and shared policy

**Files:**
- Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseClient.java`.
- Create `search/typesense/TypesenseException.java` and `search/typesense/TypesenseSearchMapper.java` under that Java instance package.
- Modify `search/service/SearchService.java`. Inspect `search/controller/SearchController.java` and preserve its GET route, permission annotation and service delegation; modify it only if those contracts require a meaningful change.
- Create `search/service/SearchAccessPolicy.java`, `SearchQueryPolicy.java`, `FieldPolicy.java`, `SearchResultBudget.java`, and `search/repository/SearchFallbackRepository.java`.
- Create tests in `apps/server/src/test/java/com/greenwhite/dwh/instance/search/`: `TypesenseClientHttpTest.java`, `SearchFallbackIntegrationTest.java`, `SearchQueryPolicyTest.java`, `SearchResultBudgetTest.java`; extend `SearchServiceTest.java`.

**Interfaces:**

```java
// Existing SearchHit remains nested in SearchService with its five fields.
public record SearchResult(String query, int totalHits, List<SearchHit> hits,
    Long foundHits, boolean hasMore, String source, boolean degraded) {}
// SearchQueryPolicy is an immutable typed value, not a raw Typesense parameter map.
public record FieldPolicy(String field, int weight, int numTypos, boolean prefix) {}
public record SearchQueryPolicy(int globalLimit, int requestsPerMinute, int burst,
    String schemaProfile, Map<String,List<FieldPolicy>> fields) {
  public static SearchQueryPolicy defaults();
}
// SearchAccessPolicy reuses the existing role authorizer and SecurityContext.
public void requireSearchAccess();
// TypesenseClient nested records: one collection result per requested category.
public record CollectionSearch(String entityType, List<SearchHit> hits, long found, long searchTimeMs) {}
public List<CollectionSearch> multiSearch(String query, String entityType, int limit,
    Map<String,String> collections, SearchQueryPolicy policy);
// Shared result-budget component does fair allocation, then group ordering.
public List<SearchHit> allocate(List<CollectionSearch> groups, int globalLimit);
```

Defaults: limit 10, rate 120/min, burst 20, profile MIXED. TASK fields `title(10,2,true)`, `description_markdown(3,2,true)`, `status_name(2,2,true)`, `project_name(2,2,true)`; PROJECT `name(10,2,true)`, `description(3,2,true)`; USER `name(10,2,true)`, `login(8,0,true)`, `email(6,0,true)`, `phone(6,0,true)`. The tuple is weight/typos/prefix. `SearchService` initially consumes defaults and legacy collection mapping through small protected seams in production constructors, not test-only methods; Task 3 replaces legacy resolution with durable state and Plan 2 replaces defaults with saved policy.

- [ ] Write real HTTP boundary RED using JDK loopback `HttpServer` and the normal TypesenseClient constructor: ALL must issue one POST `/multi_search`, preserve encoded query text, and supply category-specific fields/weights/typos/prefix plus `per_page`. Return three ordered fixtures. A response with one `code:503,error` result, missing result, invalid JSON, null body, missing/non-array hits, invalid ID or missing document must fail the entire adapter operation. A valid `found:0,hits:[]` is successful zero.

```java
assertThat(result.hits()).extracting(SearchHit::id).containsExactly("11","12","21","31");
assertThat(result.totalHits()).isEqualTo(4);
assertThat(result.foundHits()).isEqualTo(12L);
assertThat(result.hasMore()).isTrue();
assertThat(result.source()).isEqualTo("TYPESENSE");
// Three groups [11,12,13], [21,22], [31], budget four -> allocate 2/1/1.
```

- [ ] Run RED; retain old scope tests and add unauthenticated, delegated-only and active-admin cases. `requireSearchAccess` enforces both search permission and current legacy-wildcard OR active-admin scope; no network/SQL search before this gate.
- [ ] Implement strict response parsing and one HTTP request. Throw a safe typed exception without downstream body in its message/log; do not swallow partial collection failures. Keep connect 1500ms/read 3000ms and expose them as read-only budgets later. Do not compare scores across collections. Count found across groups, cap returned hits globally. Keep successful zero separate from fallback.
- [ ] Test and implement trimmed 2–200 input, enum categories, global cap 1–50; the current policy's globalLimit caps requested `limit`. `#123` uses parameterized exact positive bigint lookup, overflow is a 400. Null entity defaults ALL. Canonical target URLs remain `/tasks/items/{id}`, `/tasks/projects/{id}`, `/iam/users/{id}` for Task 5.
- [ ] Move fallback into a separate repository transaction with `readOnly=true, timeout=2`; no outer transaction spans Typesense. SQL includes deterministic `order by id`, active project/user filter, phone search, task project/status text, and limit+1 per group to derive `hasMore`. Use literal escaping rather than treating `%/_` as wildcards:

```java
String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
// SQL: field ilike :query escape '!' ; all values are bound parameters.
```

Use PostgreSQL Testcontainers to prove `%_`, phone, exact ID, group budget and active-state behavior. SQL fallback returns `source=POSTGRES`, `degraded=true`, `foundHits=null` (unless exact query count is known), not a fabricated total. Failure of fallback propagates a structured server error, not an empty list. A disabled/uninitialized engine uses the same marked fallback. Query logs never include user text.
- [ ] Extract a safe plaintext snippet from Typesense's highlight payload with empty highlight tags requested; cap displayed content at 240 Unicode code points. No trusted HTML. Validate snippets, IDs and required document fields; retain a bounded text description when a highlight is absent.
- [ ] Run focused tests then full Maven verify; commit task-only backend changes as `fix(search): make multi-search and fallback explicit and bounded`.

### Task 3: Atomic projection revisions and bounded durable delivery

**Files:**
- Create `apps/server/src/main/java/com/greenwhite/dwh/instance/search/SearchChangePublisher.java` (public module boundary).
- Create under the search package: `repository/SearchProjectionReader.java`, `SearchDeliveryRepository.java`, `SearchIndexStateRepository.java`; `service/SearchDeliveryWorker.java`, `SearchWorkerCoordinator.java`; `typesense/SearchCollectionSchema.java`.
- Replace internal use of `typesense/TypesenseIndexer.java` and `TypesenseSyncRunner.java`; remove obsolete classes once no call sites remain.
- Modify `ms/task/service/MsTaskService.java`, `MsProjectService.java`, `md/service/MdUserService.java` and the actual service owning status writes if one exists. Inspect status repository/controller: if status is read-only, expose publisher fan-out and test it without inventing a status edit endpoint.
- Extend `TypesenseClient.java`, `SearchService.java` and affected constructor-based tests.
- Create search tests `SearchRevisionIntegrationTest.java`, `SearchDeliveryIntegrationTest.java`, `SearchBootstrapIntegrationTest.java` plus focused HTTP write cases in `TypesenseClientHttpTest.java`.

**Interfaces:**

```java
// Mandatory participation: fail if called outside a business transaction.
@Transactional(propagation = Propagation.MANDATORY)
public void changed(String entityType, long entityId);
public void projectChanged(long projectId); // project + all child task revisions in same TX
public void statusChanged(long statusId);   // all child task revisions in same TX
// Reader returns source and revision from one PostgreSQL statement snapshot.
public record Projection(String entityType, long entityId, long revision,
    Map<String,Object> document, String fingerprint) {} // document null = delete/tombstone
public Optional<Projection> read(String entityType, long entityId);
// Existing state accessor consumed by queries and later job/settings tasks.
public record IndexSnapshot(UUID generationId, long version, Map<String,String> collections,
    String schemaProfile, boolean initialized, boolean legacy) {}
public IndexSnapshot snapshot();
public void runOnce(); // bounded worker cycle, also actual scheduler entry target
```

- [ ] Write real transaction RED: create task and invoke its service inside a held transaction; a separate connection/worker must not see it. Commit makes revision pending; rollback removes both business change and revision. Do not replace the publisher with a mock in these cases.
- [ ] Add concurrency tests with latches, not sleeps: an HTTP upsert of revision 1 waits; transaction commits revision 2; acknowledgement of 1 must leave 2 pending. Deletion/exclusion after queued upsert must end in absence, including an injected first HTTP failure and worker recreation. Project rename updates both project and child task search text after commit; rollback restores both projections.
- [ ] Implement coalescing with SQL in the caller's transaction:

```sql
insert into search_projection_versions(entity_type,entity_id,revision)
values (:type,:id,1)
on conflict(entity_type,entity_id) do update
set revision=search_projection_versions.revision+1, changed_at=clock_timestamp();
```

Acquire `search_index_state` singleton `FOR SHARE` before publishing and use deterministic entity/id ordering for fan-out. The later activation transaction takes `FOR UPDATE`, providing a short cutover barrier. Business mutations never wait on HTTP. For the projection query join the revision row and business source in one statement, so no old body can be acknowledged as a newer revision. Missing or excluded source generates a null document and a deterministic tombstone fingerprint. SHA-256 uses canonical sorted-key serialization of the projection with no revision field; include `_projection_revision` and `_projection_fingerprint` in index metadata.
- [ ] Implement a single `SearchWorkerCoordinator` with a non-overlapping single-thread dedicated scheduler, shutdown handling and persistent owner UUID. Recover abandoned ownership only at application lifecycle start in the supported single-process topology. Claims/acks carry owner+attempted revision; reject an old owner's callback. Do not run this scheduler in migrate profile or create an executor per request. Future job work must share this coordinator, not race a second HTTP writer.
- [ ] Worker defaults: up to 100 projections/cycle; one-cycle work/queue memory bounded. Retry after `min(300s, 2^(attempt-1)s)` with bounded jitter, terminal at 8 failures for the same revision; newer revision resets that revision's failure state. Use `Clock`/jitter collaborator for deterministic tests. Persist delivered revision/fingerprint only after actual HTTP success. Missing-document DELETE 404 is successful; missing-collection 404 is not swallowed as successful initialization. No raw error body is logged.
- [ ] Replace startup main-thread sync with coordinator background initialization. Discover all three existing legacy collections; register a LEGACY generation and mark rebuild-required, not verified-ready. Missing collections use a new generated-name background initial generation; never delete or silently reinterpret legacy data. Initial keyset discovery inserts missing projection-version rows with `ON CONFLICT DO NOTHING`, captures a cursor and resumes after restart. No `listOfRows` over all source records. Schema uses known Typesense 27.1 fields, MIXED locale/stem defaults, metadata and no `enable_phonetic`. Search falls back while there is no usable active generation. Initial generation activation may use the same minimal state repository CAS; Plan 2 adds full admin lifecycle and verification before READY.
- [ ] Wire every existing task/project/user content/state mutation to publisher, including task move/update and user block/anonymize. No direct business-module Typesense dependencies remain. If a user profile mutation bypasses `MdUserService`, route its search change through the public publisher as well; authorization-version code is otherwise unchanged.
- [ ] Add tests for outage at startup followed by eventual recovery, retry exhaustion/new revision recovery, bounded paging/restart, no startup sleep/network on ApplicationRunner, and no DB transaction held during HTTP. Run focused tests, full Maven verify, `graphify update .` (do not stage generated output); commit `feat(search): deliver committed projections through durable revisions`.

### Task 4: Dedicated search rate budget with safe live replacement

**Files:**
- Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/RateLimitFilter.java`, `RateLimitService.java`.
- Create `apps/server/src/main/java/com/greenwhite/dwh/instance/search/service/SearchPolicyProvider.java` returning defaults now and persisted policy in Plan 2.
- Create public `apps/server/src/main/java/com/greenwhite/dwh/instance/search/SearchOwnerRateLimits.java` with `userPerMinute()` and `tokenPerMinute()`; make existing `config/security/RateLimitProperties.java` implement it. Inject this interface into the provider without importing config classes into search or registering mutable copies of caps.
- Create `search/service/SearchRateBudget.java` as the small immutable `(perMinute,capacity)` value. The provider exposes `effectiveBudget(int ownerPerMinute)` for the filter and `effectiveBudgets()` for status, deriving both user/API budgets from one `current()` policy read and the same injected owner-cap source. No new configuration properties are introduced.
- Modify `SearchService.java` to consume that same provider, not separate defaults.
- Extend `RateLimitFilterTest.java`; create `RateLimitServiceTest.java` under the matching test package.

**Interfaces:** `SearchPolicyProvider.current(): SearchQueryPolicy`; `RateLimitService.tryConsume(String key,int perMinute,int capacity): ConsumptionProbe`, preserving the existing two-argument overload for unrelated paths.

- [ ] RED: after exhausting an audit bucket, the authenticated `/api/v1/search` path still has search tokens. At fixed clock 20 immediate search requests pass, the next returns 429 with positive Retry-After; changing owner separates the bucket. User/API per-minute caps below search policy remain upper bounds. Unauthenticated paths retain IP policy.
- [ ] RED live replacement: exhaust a search bucket, alternate two valid configurations repeatedly, and assert the next token is not recreated for free. Preserve consumed-token debt on policy change. Exercise the real Bucket4j instance with supported time injection, not sleeps.

```java
var probe = service.tryConsume("user:42:search", 120, 20);
assertThat(probe.isConsumed()).isTrue();
// After consuming twenty at a fixed clock:
assertThat(service.tryConsume("user:42:search", 120, 20).isConsumed()).isFalse();
assertThat(service.tryConsume("user:42:search", 119, 20).isConsumed()).isFalse();
```

- [ ] Implement interactive path matching for `GET /api/v1/search` and `POST /api/v1/search/preview` only, separately from expensive management jobs; the preview method matches the companion controller contract. Add method-specific tests rejecting interactive classification for other methods at either path. Clamp per-minute to existing user/token limits and capacity to the resulting rate. Use Bucket4j configuration replacement with no fresh token grant; preserve two-argument behavior on all other endpoints. Round Retry-After up, not down. Expose effective budget via the provider for Plan 2 status. Security log stays bounded and contains no query values.
- [ ] Run focused rate/filter/search tests and full Maven verify. Commit `fix(search): separate interactive query rate limits`.

### Task 5: Search metadata, safe direct-record navigation and UI regressions

**Files:**
- Modify `apps/web/src/app/core/models/search.models.ts`, `core/services/command-palette.service.ts`, `layout/command-palette/command-palette.component.ts`, `app.routes.ts` and their existing tests.
- Modify `features/tasks/tasks.component.ts`, `features/tasks/projects/projects.component.ts`, `features/iam/users/users.component.ts` and tests for route-aware record loading; create `core/services/search-target.ts` and `search-target.spec.ts` for the typed mapping.
- Extend authoritative `apps/server/src/main/resources/i18n/ru.json`, sync `apps/web/src/app/core/i18n/packaged-russian.ts` with task-only hunks.
- Create `e2e/tests/browser/instance/search-reliability.spec.ts` using existing authenticated synthetic fixture helpers.

**Interfaces:** Keep existing DTO hit fields; add response `foundHits:number|null`, `hasMore:boolean`, `source:'TYPESENSE'|'POSTGRES'`, `degraded:boolean`. `searchTarget(hit:SearchHit): string[]|null` accepts only TASK/PROJECT/USER with canonical positive decimal bigint string and returns the corresponding route segments; never navigate `targetUrl` supplied by a dependency.

- [ ] Use frontend testing/debugging skill. RED unit tests for a selected typed hit opening its exact ID, rejected malicious URL/invalid ID, successful empty state, degraded notice, stale cancellation and 429 retry cooldown. Preserve the already-dirty immediate clear/cancel/focus/close behavior.

```typescript
expect(searchTarget({ entityType:'TASK', id:'123', title:'x', description:'', targetUrl:'https://invalid.test' }))
  .toEqual(['/tasks/items','123']);
expect(searchTarget({ entityType:'USER', id:'../1', title:'x', description:'', targetUrl:'/iam/users/1' }))
  .toBeNull();
```

- [ ] Register `/tasks/items/:id`, `/tasks/projects/:id`, `/iam/users/:id` before conflicting generic routes, using existing page/detail components. React to route ID changes with cancellation; fetch the selected item even when it is absent from the current list page. Reuse task detail and project/user detail surfaces without granting edit permission. For project/user pages whose only existing detail is an editor, add a read-only detail state within the existing component rather than entering an editable form. Do not write on route load. Missing/inaccessible/deleted record displays localized 404 with a list/back action. Closing detail returns to the correct list URL; reload and browser back preserve record identity. Existing list filters/dirty editor protections remain intact.
- [ ] Add localized category selector ALL/TASK/PROJECT/USER, plaintext snippets, returned-versus-found/hasMore semantics and a visible SQL fallback notice. Display one inline error. `Retry-After` sets a bounded visible manual-retry countdown; typing does not generate calls before expiry and no automatic retry loop is created. Search input min 2/max 200, global limit is server-owned. Preserve keyboard selection/focus, 120ms debounce and mobile layout.

For normal palette requests, omit an explicit `limit` rather than fixing the client's default at10. The companion settings task makes an omitted API limit resolve to the saved global limit; explicit caller limits remain capped by that policy. The default policy still returns at most10 until changed by an authorized administrator.
- [ ] Add real browser cases: create synthetic task/project/user through APIs, wait on observable search indexing, query category/ID, choose hit, assert exact entity ID and refreshed detail, reload/back, exclude a project/user through its existing state API and verify disappearance from search. Exercise the localized missing-record surface through a real404 for an absent canonical ID. Passive records are excluded from search but their existing authorized detail API is not redefined as404; preserve that distinction. Task/project APIs currently have no record-delete endpoint: do not invent one for this test. Physical-deletion delivery/reconciliation remains covered by disposable backend fixtures and combined acceptance. Mock only controlled outage/429 UI cases and label them separately from real indexed-data cases. No default waits or weakened assertions.
- [ ] Run focused Angular tests, all Angular, app/E2E typecheck, i18n sync/audit and build; run new E2E on a separate migrated QA runtime after Plan 2 so the whole new stack is available. Record that deferred combined browser gate explicitly; no claim of live acceptance from unit tests. Run Graphify update without staging it. Commit task-only source/tests as `feat(search): open typed record results with honest search feedback`.

## Package acceptance and handoff

- [ ] Review each task independently, then review this package as a whole against the approved spec's package-1 criteria.
- [ ] Run Maven verify, Angular tests/typecheck/build/i18n and architecture/docs/hygiene gates on the exact code state.
- [ ] Continue with `2026-09-07-search-index-management.md` without a redundant approval question. Its final isolated E2E/performance task verifies both packages together; port 4200 remains unchanged.
