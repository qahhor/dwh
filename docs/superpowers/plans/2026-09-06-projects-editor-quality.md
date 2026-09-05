# Projects editor quality implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent accidental loss or stale replacement of project drafts and reject invalid project writes.

**Architecture:** Keep the Angular Projects component and shared modal/button contracts. Load project detail before editing, retain a field baseline, PATCH only changed fields, and isolate each request lifecycle. Validate names/states in the existing transactional project service before repository/index/audit side effects; no schema or permission changes.

**Tech Stack:** Angular 22/Vitest, Java 25/Spring Boot 4.1, PostgreSQL 18/Testcontainers, Docker Compose.

**Spec:** `docs/technical-specification.md` (FR-WORK-01, FR-IAM-05, AC-01, AC-02, AC-06), `docs/adr/ADR-0014-unified-open-source-runtime.md`, `.superdesign/design-system.md`. This bounded defect repair follows the user's request to update 4200 and continue Projects quality, not a change of product requirements.

## Global Constraints

- One installation serves one organization; server permissions and task/file row scope remain unchanged.
- Work in `main` by explicit user preference. Preserve unrelated dirty audit, graphify-out and output files. No push, remote deployment, data or volume deletion.
- Use existing `ui-modal`, `ui-button`, semantic light/dark tokens, Inter and Material Symbols Outlined. No dependency, new library, Figma or redesign.
- Russian copy belongs in `apps/server/src/main/resources/i18n/ru.json`; regenerate packaged Russian and run the localization audit.
- Existing Projects list/loading/stats/permissions and newly-created project focus must continue working.
- Existing PATCH omitted/null fields remain unchanged; description clearing is an explicit empty string. Do not introduce optimistic locking or claim full concurrent-write protection.
- Mutating tests run only against synthetic fixtures or isolated test databases, never user data on 4200.

## Deployment checkpoint (completed before implementation)

Clean `git archive 41ec91d` built matching server/web images. Compose `smartupcms` on port 4200 retained its environment and named data volumes. One-shot migrate validated 24 migrations, schema 024, no migration necessary. `up --no-deps --no-build --wait server web` succeeded, both healthy, `/healthz` 200 and server Russian dictionary includes new Projects keys. Original server/web images retained as `before-41ec91d`; build input/override under `C:/Temp/smartupcms-4200-update-2be30e0f93704a6db78ae5f99c91da07`. This is local update evidence, not a production release gate.

## Task 1: Reliable project draft and request lifecycle

**Files:**
- Modify: `apps/web/src/app/features/tasks/projects/projects.component.ts`
- Test: `apps/web/src/app/features/tasks/projects/projects.component.spec.ts`
- Modify: `apps/server/src/main/resources/i18n/ru.json`
- Generate: `apps/web/src/app/core/i18n/packaged-russian.ts`

**Interfaces:**
- Consumes: `GET /tasks/projects/{id}` returns `Project`; PATCH returns 204; `ApiService` emits normalized `ProblemDetail` and already owns error toasts.
- Produces: `requestCloseCreate()`, `requestCloseEdit()`, `confirmDiscardCreate()`, `confirmDiscardEdit()`, `retryEditLoad()`; existing create/edit entry and submit methods retain their names.

- [x] Write failing real TestBed/HTTP boundary tests for create/edit dirty close (Cancel, Escape/backdrop via modal output), confirmation cancel preserving values, confirmed discard, pristine close, pending create/edit duplicate submit, disabled fields/dismiss, save failure preserving draft, and destroyed/closed requests not applying late callbacks. Example:

```ts
component.openCreateModal();
component.createForm.name = 'Unsaved project';
component.requestCloseCreate();
expect(component.isCreateModalOpen()).toBe(true);
component.confirmDiscardCreate();
expect(component.isCreateModalOpen()).toBe(false);
```

- [x] Add failing fresh GET pending/error/retry, old detail cancellation and changed-field payload cases. After fresh `{name:'Current',description:'Current description',state:'A'}`, editing only name must produce exactly `{name:'Renamed'}`. No-change Save closes without PATCH or success toast. Description cleared sends `{description:''}`. Include GET response ID mismatch rejection and reopening cannot apply an old response.
- [x] Run focused RED: pinned Node with `node_modules/@angular/cli/bin/ng.js test --watch=false --include=src/app/features/tasks/projects/projects.component.spec.ts`; record actual expected failures before production edits.
- [x] Implement the tested local state machine: separate create/edit draft baselines and confirmation signals; `[dismissible]="!isSubmitting()"`; disabled fieldsets and Cancel/Save while saving. Guard imperative entry/submit/discard/retry methods against destroyed/pending/other-open dialog states. Invalidate and unsubscribe owned detail/mutation subscriptions on close/destroy. Opening another modal cannot reset a live draft.

```ts
const payload: Record<string, unknown> = {};
if (name !== baseline.name) payload['name'] = name;
if (description !== baseline.description) payload['description'] = description;
if (state !== baseline.state) payload['state'] = state;
```

Compare trimmed name/description consistently to normalized fresh baseline; don't rewrite untouched fields. Keep draft dirty detection based on actual entered values, so dismiss does not silently lose whitespace edits. Display recoverable detail loading/error/retry before the editor; use GET `notifyError:false`. On POST/PATCH failure show normalized `.detail` inline (`role=alert`) and do not add another toast; existing ApiService remains the single toast owner. Retain values and enable retry, without automatic mutation retries.
- [x] Add domain-specific Russian draft/loading/error keys; run `node scripts/sync-packaged-russian.mjs` and `node scripts/localization-audit.mjs` from apps/web. No semantic progress-label or hit-target redesign in this task.
- [x] Run focused GREEN, full Angular suite, typecheck and production build; self-review and commit only the four scoped files with DCO sign-off.

## Task 2: Project write validation at the service boundary

**Files:**
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/service/MsProjectService.java`
- Modify minimally: `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/repository/MsProjectRepository.java` (typed null/JSONB binding only; preserve atomic omitted-attributes semantics).
- Test (create): `apps/server/src/test/java/com/greenwhite/dwh/instance/ms/task/MsProjectWriteIntegrationTest.java`
- Controller DTO change only if essential to tested HTTP behavior; no repository/migration rewrite.

**Interfaces:**
- Consumes existing controller CreateProjectDto / UpdateProjectDto and service `createProject` / `updateProject` signatures.
- Produces structured `validation_failed` HTTP 422 with field errors for invalid supplied name/state, matching existing `ErrorCode.VALIDATION_FAILED` / `ProblemDetailRecord.ofValidation`; preserves omitted and explicit null PATCH semantics.

- [x] Write real PostgreSQL + MockMvc tests using the transaction proxy/interceptor/advice pattern in `MsTaskPatchIntegrationTest`. Verify blank `""`, `"   "`, `"\t\n"` names and state outside A/P on PATCH; invalid create state; create null/blank name through service; valid trimmed name; omitted/null fields unchanged; explicit empty description clears; missing ID 404; permissions reject unauthorized update. For every rejected write assert unchanged persisted project and audit row count.

```java
mvc.perform(patch("/api/v1/tasks/projects/" + id)
    .contentType("application/json")
    .content("{\"name\":\"   \",\"description\":\"must not persist\"}"))
    .andExpect(status().isUnprocessableEntity())
    .andExpect(jsonPath("$.code").value("validation_failed"));
assertThat(projects.findById(id).orElseThrow().description()).isEqualTo("Original description");
```

- [x] Run focused RED with `mvn -B -pl apps/server -am -Dtest=MsProjectWriteIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`; no skipping PostgreSQL/Docker tests. Controller tests keep actual service/repository/audit and mock only external indexing/custom-field dependencies where unused.
- [x] Add service-local validation before side effects; require nonblank create name, reject a non-null blank PATCH name, trim valid supplied names, accept only A/P for non-null state. Preserve null create default A and null PATCH no-op. Use `ApiException.validation` with `FieldErrorItem` and existing exception advice for meaningful 4xx.

```java
if (state != null && !state.equals("A") && !state.equals("P")) {
    throw ApiException.validation("Недопустимый статус проекта", List.of(
        new FieldErrorItem("state", "invalid", "Допустимые значения: A, P")));
}
```

Verify the existing FieldErrorItem constructor before writing; retain lookup-before-validation for missing update IDs and no partial index/audit side effects on validation failure. Audit reflects normalized persisted name. Null/omitted attributes must retain the database row's current attributes atomically, not a service snapshot; add non-empty attribute assertions and a deterministic overlapping-transaction regression.
- [x] Run focused GREEN and full Maven reactor verify with Testcontainers, self-review and DCO commit scoped implementation/test files.

## Acceptance and handoff (controller)

- [x] Independent task reviews and final whole-change review on exact commit ranges.
- [x] Verify changed source/tests, rerun relevant combined gates; `graphify update .` AST-only without publishing dirty output.
- [x] Validate create/edit draft, pending/failure/fresh values on an isolated UI fixture at desktop and 390px in light/dark. Record console and screenshot evidence outside Git. No mutations on user installation.
- [x] Update AI context with actual outcomes and remaining W-P09/W-P11/E2E limits, not assumptions. Keep first deployment checkpoint distinct from subsequent code changes.

## Implementation evidence

Application commits: `b321599`, `8912e00`, `0e5803f`, `9aa23e1`, `54b6159`.
Frontend task review: `41ec91d..8912e00`, no findings. Backend task review:
`8912e00..0e5803f`, two Important findings about stale attributes and missing
non-empty/overlap assertions; scoped fix review `0e5803f..9aa23e1` accepted
both. Final whole-change review `41ec91d..9aa23e1` found one normalized-name
edge case; the single final fix wave `9aa23e1..54b6159` is accepted. No open
Critical/Important findings. Existing Java/JNA/Testcontainers warning noise is
a deferred non-blocking minor, not pristine output or a reason to upgrade
the toolchain in this package.

| Gate | Result |
|---|---|
| Angular/Vitest | 32 files / 179 tests, 0 failures; Projects 21/21 |
| TypeScript, i18n, production frontend build | Pass; 1036 referenced / 1059 Russian keys |
| Maven reactor at final application tree | 364/364 (server 359, libraries 5), 0 failures/errors/skips |
| PostgreSQL project write tests | 19/19, real migrations, permissions, audit and transaction boundaries |
| Frontend behavioral RED | Three compiled DOM tests against clean 41ec91d, 3 assertion failures / 0 errors |
| Backend behavioral RED | Initial 17/15 expected failures; overlap 1/1 failure; control-only name 4/1 failure |
| Extra real-browser save regression | b321599 returned500; unchanged scenario on9aa23e1 passed1/1 |
| Final combined Docker E2E at54b6159 | 31/31, 2.3m; additional editor scenario1/1 |

UI fixture: standalone synthetic frontend on14201 with no upstream or
credentials; desktop1280x720 and mobile390x844, light/dark. Tested dirty
create/edit Cancel and Escape, confirmation cancellation retaining exact
draft, pending fields/dismiss guards, fresh detail/error/retry, mutation
failure preserving draft, sparse PATCH, no-change Save and explicit empty
description. Mobile document390/390, final fixture console warnings/errors
empty. These are not server-role tests or authenticated4200 mutations.
Snapshots/scripts/test outputs live outside Git at
`C:/Temp/smartupcms-projects-editor-qa-20260906/`.

### Decisions during implementation

1. Retain shared HTTP422 / lowercase `validation_failed`, correcting the
   plan's original400/uppercase example. Evidence: `ErrorCode.java` and
   `ProblemDetailRecord.ofValidation`. A consumer requiring400 needs a
   separately reviewed compatibility change, not a silent global rewrite.
2. Allow one minimal repository JSONB expression change after real PostgreSQL
   exposed untyped null failure. A service snapshot workaround violated
   atomic omitted-field preservation; regression tests now reject that race.
   The cost of a wrong SQL decision is PATCH compatibility, checked before
   deployment by real PostgreSQL sparse/overlap tests and the reactor gate.

No schema, dependency, permission or row-scope changes. Next bounded package:
truthful terminal-task labels and action sizing/keyboard form semantics
(W-P09/W-P11 references remain audit navigation, not new requirements), then
permanent Projects browser regressions. Full optimistic locking remains out
of scope. This evidence is local acceptance, not production readiness.

## Final local update — completed

Clean archived54b6159 built matching server/web images, first accepted on
the isolated candidate and then promoted unchanged to local4200. Candidate
and local migration checks validated24 migrations, schema024, no migration
necessary. Local Compose `up --no-deps --no-build --wait server web` exited0.
All four local services healthy, healthz200, new public Russian keys match
the canonical source. Server environment and all named mounts match the
pre-update snapshot; PostgreSQL/Typesense image IDs and start times are
unchanged. Only server/web were recreated. Prior41ec91d images retained for
rollback; no push, external deployment, volume deletion or customer-data
test writes.

Tested/promoted local image IDs:

- server: `sha256:d98b8d72227c3f5c127a50a8e9b5606affb024cf8779fa83f8ba97b7905682e1`
- web: `sha256:85a2a2b44a6b209a98cbaf90b60fdbd47e0fddea9366e6d47d97b16a7595d8a4`

The auxiliary final editor test initially hit a Playwright/CDP body-reading
protocol error after successful POST201 and a visible created row. It now
derives ID from the fresh detail response URL, retaining HTTP status, sparse
payload and persisted-value assertions. Recheck passed1/1; failed and passed
artifacts are both retained externally. No application change was made for
this test-transport issue. The existing31-scenario suite passed on final
images. Config3/3, E2E typecheck and artifact-secret checks also passed during
this package; no permanent E2E source was changed.

Final IAB4200 login renders with no console warnings/errors; authenticated
editor mutations were checked only on the isolated candidate. Fixture14201
and candidate14200 remain available for subsequent QA. Viewport override is
reset at handoff; user Chrome is untouched. AI context updated. Public-docs
(105 Markdown/19 required), hygiene, architecture and whitespace gates pass.
