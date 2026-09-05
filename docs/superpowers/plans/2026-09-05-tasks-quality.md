# Tasks Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct all fourteen accepted Tasks audit findings without adding product scope or changing customer data.

**Architecture:** Preserve the Angular Tasks screen, existing shared controls and Spring modular monolith. Make task PATCH presence-aware and transactional; use explicit request state and independent paginated selectors on the client. Keep authorization at the server boundary and use isolated tests for writes.

**Tech Stack:** Angular 22, TypeScript 6, RxJS, Vitest; Java 25, Spring Boot 4.1, PostgreSQL 18, Flyway, Playwright.

**Spec:** `docs/technical-specification.md` FR-WORK-01–04, FR-IAM-05, AC-02/06/10; `docs/adr/ADR-0013-data-scope.md`; existing `.superdesign/design-system.md`. The user approved implementing the dated Tasks audit; finding IDs below are traceability labels, not a replacement specification.

## Global Constraints

- Work in `D:/Claude/dwh` on `main`, per the user's explicit existing instruction. Do not create branches, push or deploy in this implementation turn.
- Preserve unrelated dirty Graphify output, local audit drafts and `output/`; never stage them with task commits.
- Browser обращается только к server API. Авторизация всегда выполняется на сервере; Typesense не принимает решений о доступе.
- List/detail/stats/subtasks/ancestors/members/attachments и все изменения должны применять row scope `ALL/SUBTREE/UNITS/SELF` на сервере; недоступный прямой ID возвращает `404`.
- Система должна назначать пользователей на задачи только после проверки разрешённой области инициатора.
- No new dependencies, no rewriting published migrations, no live database fixtures or deletion of user data.
- Keep the existing restrained enterprise aesthetic. Keep the navigation sidebar intentionally dark in both themes. Content surfaces use semantic theme tokens.
- Add Russian UI strings in `apps/server/src/main/resources/i18n/ru.json`, run `npm run i18n:sync-ru` then `npm run i18n:audit`; preserve per-key Russian fallback.
- Use TDD: observe behavioral assertion failures before implementation, then focused green tests. Local task commits are allowed, with exact file staging; no external publication.
- Subagents do not spawn other subagents. Only one implementation subagent runs at a time. The controller owns independent review and final integration verification.

## Verification commands

PowerShell frontend from `apps/web`:

```powershell
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe' node_modules/@angular/cli/bin/ng.js test --watch=false --include='src/app/features/tasks/**/*.spec.ts'
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe' node_modules/typescript/bin/tsc --noEmit -p tsconfig.app.json
```

PowerShell backend from repo root (JAVA_HOME is already an existing Java runtime setting; do not print secrets):

```powershell
& 'D:/Claude/dwh/output/tools/maven/bin/mvn.cmd' -pl apps/server -am test '-Dtest=MsTaskServiceTest,MsTaskCommentServiceTest,TaskFileDataScopeControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Use concrete new test classes in the focused command as each is added. Before final completion run full Maven verify, all frontend tests, typecheck/build/i18n checks and the relevant isolated browser suite. Red/green evidence belongs in the plan-specific ignored ledger, not in customer logs.

### Task 1: Correct server task update and comment contracts

**Findings:** W-03, backend portion of W-07; prerequisites for safe editing.

**Files:** Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/controller/MsTaskController.java`, `service/MsTaskService.java`, `repository/MsTaskRepository.java`, `repository/MsTaskCommentRepository.java` within that same task package. Add a focused typed patch request/value class in that package if needed, without a generic patch framework. Add/extend tests under `apps/server/src/test/java/com/greenwhite/dwh/instance/ms/task/`; adapt existing controller tests only for changed internal signatures.

**Interfaces:** Existing `PATCH /api/v1/tasks/{id}` (and items alias) JSON field names remain unchanged. Missing field leaves value unchanged; explicit null clears `projectId`, `parentTaskId`, `responsibleUserId`, `beginTime`, `endTime`. `observerUserIds: []` clears observers; omitted observer list leaves them unchanged. Invalid non-null values still fail server validation. A single application-service transaction owns the whole update including member changes and audit. Comment list/create responses retain existing fields and add nullable `userName` / `userLogin`; avoid exposing extra IAM/PII data.

- [x] Write HTTP/real-PostgreSQL tests that distinguish missing versus null and assert persisted values, rollback on invalid participant, scope rejection, and comment author display fields. The independent expectations include:

```java
// On an existing task with project, parent, owner and deadline:
patch("{\"title\":\"Renamed\"}"); // all other persisted fields unchanged
patch("{\"projectId\":null,\"parentTaskId\":null,\"responsibleUserId\":null,\"endTime\":null}");
assertThat(reloaded.projectId()).isNull();
assertThat(reloaded.parentTaskId()).isNull();
assertThat(reloaded.endTime()).isNull();
// invalid participant + valid title in the same PATCH must leave the old title/members intact.
```

- [x] Run the focused tests, confirm failures are caused by coalesce/null gating or missing author fields rather than test setup.
- [x] Implement presence-aware decoding using existing Jackson support and typed validation; pass explicit field-presence information to a transactional service. SQL must distinguish missing from explicit clear without changing legacy callers' semantics. Validate task, project/parent and participants before writes; never weaken row scope. For authors, join only display identity, with null-safe handling for removed identity; ensure create and list have consistent response shape.
- [x] Run focused green tests plus the existing task/scope/comment tests. Review for partial commits, accidental scope bypass, serialization compatibility, and duplicate audit events.
- [x] Stage exact server files and commit `fix(tasks): preserve patch semantics and comment authors`; write report with test commands and RED/GREEN output.

Verified 2026-09-05: commit `482e2f0`; semantic RED 3 expected failures, focused GREEN 12/12, neighboring task/scope/comment 37/37, full reactor verification (server 340/340 plus libraries 5/5). Independent task-scoped review approved without findings. No live database changes.

### Task 2: Make editing and detail state safe

**Findings:** W-02, W-04, W-05, W-13; frontend portion of W-07.

**Files:** Modify `apps/web/src/app/features/tasks/tasks.component.ts`, `.spec.ts`, `apps/web/src/app/core/models/task.models.ts` as necessary; create `apps/web/src/app/features/tasks/task-form-value.ts` and `.spec.ts` for time/payload mapping if useful. Modify shared `ui-modal.component.ts` and tests only for a backwards-compatible initial-focus/topmost-dialog behavior needed by Tasks. Update Russian catalog and generated packaged Russian dictionary.

**Interfaces:** Consume Task 1 PATCH wire semantics and author fields. Existing task property names remain unchanged. Shared pure helpers should expose `toLocalDateTime(iso: string | null | undefined): string` and `toTaskInstant(value: string, original?: string | null): string | null`. They convert browser-local time and preserve the exact original instant when its visible value is unchanged. Request cancellation/identity must protect list/detail/edit/comments and component destruction. Do not add polling or a new state library.

- [x] Add real-component tests for edit GET failure (no save-ready form), fresh response used instead of stale row, out-of-order detail/comments, closing during load, comment draft separation, repeated submit, dirty dismissal. Add date round-trip tests with fixed UTC+5 expectations and unchanged seconds/milliseconds:

```typescript
expect(toLocalDateTime('2026-09-05T12:00:37.123Z')).toBe('2026-09-05T17:00'); // Asia/Tashkent test environment
expect(toTaskInstant('2026-09-05T17:00', '2026-09-05T12:00:37.123Z')).toBe('2026-09-05T12:00:37.123Z');
expect(toTaskInstant('', '2026-09-05T12:00:00Z')).toBeNull();
```

- [x] Run RED before changing implementation. Use controlled Observable responses at the HTTP boundary, not a mocked Tasks component. Cross-timezone helper checks must not depend on the host's accidental zone.
- [x] Implement explicit loading/error/retry states for list/detail/edit and auxiliary detail reads. Preserve or label stale list data; never show false empty results after an error. Prefer cancellation plus current request identity. Editing uses `res.task` and `res.members`; failure cannot fabricate empty members. Dates show browser zone; unchanged values preserve original precision. Associate comments with the selected task and prevent duplicate posting. Gate comment creation by the existing `tasks.comments` permission.
- [x] Add dirty confirmation through the existing modal/confirmation pattern; unchanged form closes directly. During saving prevent duplicate requests and ambiguous dismissal. Moving detail → edit must leave one active modal and preserve a clear return path. Keep labels and field errors intact.
- [x] Run all affected focused tests, typecheck and i18n sync/audit; commit exact files as `fix(tasks): guard editing and asynchronous detail state` and write report.

Verified 2026-09-05: commits `5427c71`, `72f72c8`; focused 30/30 and full frontend 127/127 before review, then additional pending-write RED 2 expected failures and Tasks GREEN 28/28 after the reviewed fix. Typecheck and i18n passed. Independent review found pending-write input loss; disabled create/edit fieldsets and duplicate-create guards closed it, and scoped re-review approved. Selector-only Escape remains explicitly assigned to Task 4.

### Task 3: Correct task navigation, kanban filters and selectors

**Findings:** W-01, W-06, W-11.

**Files:** Modify Tasks component/tests; shared `ui-pagination.component.ts`/tests only if using its cursor mode; shared `ui-searchable-select.component.ts`/tests and `ui-user-multi-select.component.ts`/tests for opt-in remote search and load-more affordances. Update catalog/packaged dictionary. Existing non-Tasks consumers must retain local behavior.

**Interfaces:** Use existing `GET /tasks` keyset response `{items,nextCursor,hasMore}` and existing scoped IAM list contract, respecting action permissions. Task list cursor state is independent from parent selector state and user selector state. Do not increase a fixed limit or load the whole DB. Shared selectors may add opt-in `remoteSearch`, `loading`, `loadError`, `hasMore` inputs and `searchChange`, `loadMore`, `retry` outputs; defaults preserve current behavior. Retain selected labels when a query page is replaced.

- [x] Write tests for a 125-task fixture split into server pages; navigation reaches all IDs without duplicates and never implies loaded count is total. Filter changes reset page/cursor; old answers cannot replace a new query. Test same task set across table and kanban for active/all filters. Test selectable user beyond the first 100 and a parent outside current list filter/page.

```typescript
// Distinct backend fixtures: first page ids 1..50 + hasMore, then 51..100, then 101..125.
// Follow visible next/load-more controls and assert id 125 is reachable.
// Change filter while on a later page and assert first filtered item is rendered.
// Remote selector search returns a user id 501 absent from its initial response.
```

- [x] Run RED; confirm truncation/hidden-filter/local-selector behavior fails the assertions.
- [x] Implement real cursor navigation with known previous cursors (or explicit load-more of bounded pages) and honest loaded-result copy. If using `ui-pagination.cursorMode`, fix `goToPage` and count rendering so next is controlled by `hasNextPage`, not local totalPages; regression-test other consumers. Keep the active/all filter visible in both views. Status changes must maintain current filtering and not retain terminal tasks under active mode.
- [x] Add independent paginated remote search to parent and user/observer selectors with explicit loading/error/retry and debouncing/cancellation. Preserve selected identity labels from fresh detail members; don't silently drop IDs outside loaded search results. Do not grant IAM permission or add a broad unauthorised endpoint to make a selector work.
- [x] Preserve the fresh parent/responsible/observer baseline independently of lookup results. Omit unchanged scoped assignment fields from PATCH (observer comparison is set-based); still send explicit null/[] when cleared. Regression-test title-only edits with selected identities absent from the allowed lookup, avoiding unnecessary scope revalidation/assignment notifications. Keep backend validation unchanged for actual assignment replacements.
- [x] Run affected component tests and full shared-control tests, typecheck/i18n; commit `fix(tasks): paginate results and assignment lookups` and write report.

Verified 2026-09-05: commits `81e0e45`, `b96f2c3`; initial focused45/shared26/full139 tests passed. Independent review identified rapid-page/debounce races, empty-page navigation and stale labels; fix-round RED6 expected failures followed by focused38/shared27/full145 passing, typecheck/i18n pass. Scoped re-review approved all four findings without new breakage. Backend authorization and API remain unchanged.

### Task 4: Complete keyboard, contrast, search and compact UI repairs

**Findings:** W-08, W-09, W-10, W-12, W-14.

**Files:** Tasks component/tests, existing shared controls only where needed, Russian catalog/packaged dictionary. No replacement theme or icon/font library.

**Interfaces:** Consume Task 2 request guards and Task 3 cursor reset/search behavior. Search should apply after 350ms debounce and immediately on Enter; avoid a second duplicate request after Enter. Keep current filter choice across view changes. Export endpoint remains format-only and actor-scoped, with explicit UI copy that it exports all accessible tasks.

- [x] Add tests that Enter/Space on a nested edit/status/kanban-move control cannot open task detail, one dialog is active, Escape closes an open selector without dismissing its editor, details comment/status have accessible names, export scope is explicit, and typing/clearing search results is deterministic. Preserve a named native button for opening each task so existing E2E can use it.

```typescript
// dispatch bubbling KeyboardEvent('keydown', {key:'Enter', bubbles:true}) at edit button
// then activate the button and assert exactly one dialog with the edit name.
// For search: advance 349ms => no new HTTP request; 1ms more => current query request.
```

- [x] Run RED before changing the DOM/handlers.
- [x] Restore table row semantics; put an accessible native task-open button in title cell. Remove conflicting row keydown handlers, preserve pointer convenience only with a safe interactive-child check if retained. Prevent drag affordances without update permission. Use readable semantic status text and separate colored dot/border for all repeated status appearances. Do not change custom status colors in the database.
- [x] Improve filtered/first-empty recovery actions using existing buttons. Label export «Экспорт всех доступных задач» with filters-not-applied clarification. Replace technical form labels with «Ответственный», «Описание» and consistent «Динамические поля». At mobile width prioritize New Task, compact secondary actions and spacing, and keep short filter labels unwrapped. Keep form fields light and table scroll local.
- [x] Run focused tests and full frontend tests/typecheck/build/i18n, commit exact files `fix(tasks): polish accessible task interactions`; write report.

Verified 2026-09-05: commits `0a5c1dd`, `c4d33d4`; initial behavioral RED 11 expected failures, focused GREEN 54/54. Review found missing semantic text color on the native status select; direct-control RED 1 failure and GREEN 45/45 closed it. Scoped re-review approved with no new breakage. Controller reran the final tree: frontend 157/157 (32 files), app typecheck and production build exit 0, i18n 1019 referenced / 1039 catalog keys. Full backend verify also passed: server 340/340 plus libraries 5/5. Live browser acceptance remains Task 5.

### Task 5: Isolated browser regression coverage and final evidence

**Findings:** acceptance of W-01 through W-14.

**Files:** Extend `e2e/tests/browser/instance/tasks.spec.ts` or add `tasks-quality.spec.ts`; use existing `e2e/support` fixtures. Update `docs/ai-context.md` and this plan with verified results, not optimistic release claims. Do not commit screenshots, traces, report scratch, local audit drafts or dirty Graphify outputs.

**Interfaces:** Existing login and isolated Compose browser harness; never run mutating test journeys against the user's `localhost:4200` persistent database. Browser fixtures for HTTP error/race/large data may use Playwright routing inside test code. Never weaken production auth or ship test-only app endpoints.

- [x] Add browser tests for date round-trip and clear/reopen, preserved observers, named author, keyboard edit, dirty cancel, visible kanban filters, error/retry and mobile 390px overflow. Use real server writes only in isolated runtime; controlled transport mocks for failure/race/125-task cases. Existing happy-path project→task→comment continues to pass.
- [x] Build candidate server/web images and start separate Compose project/ports/volumes using existing safe isolated workflow. Verify test target configuration before running mutations. Run full Maven verify, frontend tests/typecheck/build/i18n and the isolated E2E suite.
- [ ] Inspect rendered desktop/mobile candidate in the available browser tool, screenshot key fixed states and read console. Store screenshots outside committed source. Do not switch the user's installation to candidate images without a deploy request.
- [x] Run `graphify update .` AST-only and leave generated dirty output uncommitted. Update context and plan with actual counts, runtime location and unresolved limitations. Commit only test/docs changes as `test(tasks): cover task quality regressions`.
- [ ] Final whole-change review; address substantive findings through one reviewed fix batch. Deliver implementation status, commands/results, browser evidence and remaining risks. Do not claim production release readiness or push/deploy.

Automated Task 5 evidence, 2026-09-05: clean archive `c4d33d4` ran as
Compose project `smartupcms-tasksq-ecb2e05e` on loopback ports 14200/15435/18118
with unique server/web image tags and empty migrated volumes. E2E config 3/3,
E2E typecheck and artifact-security passed; all 30 browser scenarios (24
existing + 6 new Tasks cases) passed in 2.3 minutes. The existing task vertical
slice first reproduced its obsolete description-label failure, then passed
with exact label `Описание`. A pre-existing localization fixture initially
timed out on a self-removing Settings language button; request tracing showed
the dictionary GET completed but the Playwright action never settled. Driving
the persistent header language selector exercised the same real preference
PATCH and left all persistence/cleanup assertions intact; the isolated rerun
and full suite passed. Root's final-tree application checks remain 157/157
frontend tests, app typecheck/build, i18n 1,019/1,039 and Maven server 340/340
plus libraries 5/5. Candidate `/healthz` returned 200 and all four services
remained healthy after E2E. External runtime and test artifacts are under
`C:/Temp/smartupcms-tasks-quality-ecb2e05e0db34d62acd838c8702b5cc6`.
Live IAB visual evidence and final whole-change review remain pending; this is
not production deployment or readiness evidence.
