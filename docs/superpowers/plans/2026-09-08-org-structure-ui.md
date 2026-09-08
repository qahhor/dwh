# Organization Structure UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver organization-tree administration, explicit user positions, role data rules and accurate effective-scope inspection without changing existing assignments automatically.

**Architecture:** Existing md services own writes, transactions, authorization contracts and scope recalculation. Angular gets a lazy feature with a typed API and small independently owned tree/editor/user/role components, embedded into existing IAM pages. No global menu rewrite, new runtime or database schema is needed.

**Tech Stack:** Existing Java 25/Spring Boot/PostgreSQL/JdbcClient/Testcontainers and Angular 22/Vitest/Playwright; pinned repository Node 24.15.0 and Maven runtime.

**Spec:** [Approved package design](../specs/2026-09-08-org-structure-ui-design.md). User requested implementation after the written design on 2026-09-08.

## Global Constraints

- Работа в текущем `main` согласно указанию пользователя.
- Автоматических назначений, смены правил существующих ролей и миграции сотрудников при открытии страницы не будет.
- Права меняются только явным сохранением соответствующей формы уполномоченным администратором.
- Существующие dirty UI-файлы сохраняются; app-shell меняется только для нового пункта и соответствующих tests.
- Никакого автоматического включения внешних сервисов, push или deploy в этом пакете.
- Все статические строки — семантические `iam.org_units.*` / `iam.data_scope.*` ключи серверного RU registry и packaged fallback.
- Новые ETag/revision поля и глобальная optimistic-locking схема не вводятся.
- `orgUnitIds` is the explicit assignment set; `legacyOrgUnitId` is separate read-only context; `visibleOrgUnitIds` is a derived result, never an editor seed.
- UI permissions do not replace server `iam.org_units` view/create/update/delete/assign permissions.
- Tests that write data use synthetic Testcontainers/candidate installations, never the working installation at localhost:4200.

## Commands and baseline

Run PowerShell from `D:/Claude/dwh`. Backend prefix:

```powershell
$env:JAVA_HOME = 'C:/Tools/Java/jdk-25.0.2'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
& ./output/tools/maven/bin/mvn.cmd -pl apps/server -am '-Dtest=MdOrgUnitControllerTest,MdOrgUnitReadContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q
```

Frontend from `apps/web` with `D:/Claude/dwh/output/tools/node-v24.15.0-win-x64` first in PATH:

```powershell
node node_modules/@angular/cli/bin/ng.js test --watch=false --include='src/app/features/iam/org-units/**/*.spec.ts'
node node_modules/typescript/bin/tsc --noEmit -p tsconfig.app.json
```

Baseline source HEAD is design commit `8215558`; seven source files and six Graphify outputs are already dirty. Stage only task-owned files/hunks, never `git add .`. Backend task commits do not include existing web changes. No baseline test claim is assumed from earlier passes.

## Task 1: Explicit assignment/rule read contracts and safe input

**Files:**
- Create `apps/server/src/main/java/com/greenwhite/dwh/instance/md/dto/MdOrgUnitDtos.java`.
- Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/md/controller/MdOrgUnitController.java`.
- Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdScopeService.java`.
- Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/md/repository/MdScopeRepository.java`.
- Create `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdOrgUnitControllerTest.java` and `MdOrgUnitReadContractTest.java` in the same test directory.

**Interfaces:**
- Consumes existing `getUserOrgUnitIds(Long)`, `findUserOrgUnit(Long)`, `userExists(Long)`, `getRoleRule(Long)`, `getUserScope(Long)`.
- Produces `MdOrgUnitDtos.UserAssignments(Long userId, List<Long> orgUnitIds, Long legacyOrgUnitId)` and `RoleRule(Long roleId, String rule)`.
- Produces `MdScopeService.getUserAssignments(Long): UserAssignments`, `getRoleScopeRule(Long): RoleRule`, repository `roleExists(Long): boolean`.
- HTTP GET `/api/v1/iam/org-units/users/{userId}` and `/roles/{roleId}/rule`, both view-protected. Existing GET effective-scope validates user existence.

- [ ] Write MockMvc tests using existing `RequiresPermissionInterceptor`, principal and `GlobalExceptionHandler` patterns; assert new paths deny anonymous/no-view and return DTOs without conflating sets. Use current service mocks; before new methods exist, first prove missing route fails expected HTTP status.

```java
mvc.perform(get("/api/v1/iam/org-units/users/42"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.orgUnitIds[0]").value(7))
    .andExpect(jsonPath("$.legacyOrgUnitId").value(9));
```

- [ ] Run focused controller test, record actual RED assertions before adding the API.
- [ ] Implement DTOs and guarded GETs; services validate positive IDs, reject nonexistent records with domain 404, return sorted explicit IDs and nullable legacy ID. `getRoleScopeRule` validates existence before repository's ALL fallback.

```java
public UserAssignments getUserAssignments(Long userId) {
    requireUser(userId);
    return new UserAssignments(userId,
        scopeRepository.getUserOrgUnitIds(userId).stream().sorted().toList(),
        scopeRepository.findUserOrgUnit(userId).orElse(null));
}
```

- [ ] Add service tests for unknown IDs, default existing role ALL, explicit assignment versus effective descendants, no write on GET; add PUT validation cases missing/null IDs, null element, zero/negative, duplicate, empty list. Service validation runs before repository replacement, and the DTO rejects an absent/null list rather than clearing it.
- [ ] Run RED for those conditions, implement with domain validation and `List.copyOf(new TreeSet<>(requested))`, rerun focused tests and existing `MdScopeServiceIntegrationTest,MdAssignmentServiceIntegrationTest`.
- [ ] Review exact diff and commit only backend task files with message `feat(iam): expose organization assignment and role scope reads`.

## Task 2: Atomic tree editing and complete scope recalculation

**Files:**
- Modify `md/controller/MdOrgUnitController.java`, `md/service/MdOrgUnitService.java`, `md/service/MdScopeService.java`, `md/repository/MdOrgUnitRepository.java`, `md/repository/MdScopeRepository.java` under the same server source root.
- Modify same-domain callers `md/service/MdAssignmentService.java`, `MdUserService.java`, `MdRoleService.java` only where needed to share mutation ordering with scope-affecting writes.
- Create `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdOrgUnitWriteIntegrationTest.java`; extend Task 1 controller tests.

**Interfaces:**
- Presence-aware patch `UpdateOrgUnitDto` uses setter-tracked `parentIdPresent` and delegates to `update(id, parentIdPresent, parentId, name, kind, state, orderNo)`. Keep existing Java overload as an explicit-parent delegate for existing callers/tests.
- `MdScopeRepository.lockScopeMutation(): void` runs transaction-scoped PostgreSQL `pg_advisory_xact_lock(129632, 1)`; key is scoped to this database's IAM scope writes, not a JVM lock.
- `MdScopeService.acquireMutationLock(): void` delegates within the caller's transaction. Acquire before relevant row changes in tree CRUD, assignment/rule PUT, user role replacement and role lifecycle. Avoid acquiring the lock only after taking per-user write locks.
- `getUserIdsAffectedByUnit` returns users assigned to the union of the node's descendants and ancestors. An update captures affected IDs before and after, unions/sorts them and recalculates each once. Create captures the new node's ancestors; delete captures before removal.

- [ ] Add HTTP RED: PATCH `{ "name": "Renamed" }` preserves parent; explicit parent change moves it; null parent on a non-root conflicts; invalid state/name/code/ID gives controlled failure.
- [ ] Add PostgreSQL RED with real Spring transactional services or TransactionTemplate: managers on old and new parent roots using SUBTREE; move a child, assert old manager loses child, new manager gains it, both versions increment, unaffected sibling isn't changed. Include create-child and activate/deactivate cases.

```java
assertThat(scopeService.getUserScope(oldManager).visibleOrgUnitIds()).contains(child);
transaction.executeWithoutResult(s -> orgUnitService.update(child, newParent, null, null, null, null));
assertThat(scopeService.getUserScope(oldManager).visibleOrgUnitIds()).doesNotContain(child);
assertThat(scopeService.getUserScope(newManager).visibleOrgUnitIds()).contains(child);
```

- [ ] Run `-Dtest=MdOrgUnitControllerTest,MdOrgUnitWriteIntegrationTest`, record failed assertions.
- [ ] Implement presence tracking, trim/nonblank/state validation, explicit parent/root checks and code conflict mapping. Update transaction/audit snapshots with the actual final parent, not absent input. Guard deletion of occupied nodes without cascade.
- [ ] Implement the advisory lock and ancestor/descendant recalculation contract; acquire before writes at scope-affecting application entry points. Keep constructors compatible where possible. Role activation recalculates scope as well as permissions; do not broaden role grant rules.
- [ ] Add concurrency test using two independent transactions, latches and bounded Futures: opposing moves A→B/B→A must yield one success and one conflict with an acyclic final tree. Add rollback-on-audit-failure assertion for data, scope and permission version. Test no arbitrary sleeps and no mock-only concurrency claim.
- [ ] Run targeted PG/controller tests and full Maven verify. Review transaction ordering and 404/409/422 contracts, then commit the task-owned backend changes.

## Task 3: Typed feature API, tree and editor page

**Files:** create under `apps/web/src/app/features/iam/org-units/`:
- `org-units.models.ts`, `org-units-api.service.ts`, `org-units-api.service.spec.ts`;
- `org-unit-tree.component.ts/.html/.css/.spec.ts`;
- `org-unit-editor.component.ts/.html/.css/.spec.ts`;
- `org-units.component.ts/.html/.css/.spec.ts`;
- `org-unit-tree.ts`, `org-unit-tree.spec.ts` for pure ordered-tree/descendant helpers;
- feature-local `org-unit-draft.ts` for shared draft leave/pending coordination if both panels and page need it.
- Modify `apps/server/src/main/resources/i18n/ru.json` and generated `apps/web/src/app/core/i18n/packaged-russian.ts` through the repository sync command.
- Modify `apps/web/src/app/core/services/api.service.ts` and its focused tests to add optional `ApiRequestOptions = {}` to `patch` and `delete`, matching existing get/post/put defaults. Existing callers retain toast behavior; only new inline-error owners opt out.

**Interfaces:**

```ts
export type ScopeRule = 'ALL' | 'SUBTREE' | 'UNITS' | 'SELF';
export interface OrgUnit {
  id: number; parentId: number | null; code: string; name: string;
  kind: string; state: 'A' | 'P'; orderNo: number;
  createdAt: string; modifiedAt: string;
}
export interface UserAssignments { userId: number; orgUnitIds: number[]; legacyOrgUnitId: number | null; }
export interface RoleRuleSnapshot { roleId: number; rule: ScopeRule; }
export interface UserScope { rule: ScopeRule; visibleOrgUnitIds: number[]; }
export interface OrgUnitCreate { parentId: number | null; code: string; name: string; kind: string; orderNo: number; }
export type OrgUnitPatch = Partial<Pick<OrgUnit, 'parentId' | 'name' | 'kind' | 'state' | 'orderNo'>>;
```

API methods return Observables: `list()`, `get(id)`, `create(body)`, `update(id, patch)`, `remove(id)`, `assignments(userId)`, `saveAssignments(userId, orgUnitIds)`, `scope(userId)`, `roleRule(roleId)`, `saveRoleRule(roleId, rule)`. Every error-owning call uses shared ApiService options `{notifyError:false}`.

Existing server JSON omits nullable root `parentId` under global NON_NULL. Normalize omitted `parentId` to `null` at this feature's API boundary for list/get/create responses; do not change the existing wire response just to satisfy a TypeScript shape. Test both omitted and explicit-null root parents.

- [ ] Write API contract tests for exact paths/body and no implicit writes; pure tree tests for orderNo/id order, descendant exclusion and orphan safety. Prove tests fail before implementation, then add types/API/helpers.
- [ ] First add HTTP-error RED for real ApiService PATCH/DELETE: default options emit one toast, `{notifyError:false}` emits none and propagates ProblemDetail. Extend those two methods to pass options to `handleError`; do not add a feature-specific HttpClient bypass.

```ts
service.saveAssignments(42, []).subscribe();
expect(api.put).toHaveBeenCalledWith('/iam/org-units/users/42', { orgUnitIds: [] }, { notifyError: false });
```

- [ ] Add component RED cases: empty creates root; view-only has no write controls; name-only edit does not emit parentId; malformed parent is excluded; tree reload failure retains error/retry rather than empty; selected node survives successful refresh.
- [ ] Implement tree as nested lists with explicit expand/select buttons. Editor uses native form, frozen target, pending lock, cancel/discard confirmation, field error association and stored original values. Page owns mutations and refresh status; no automatic retry after successful save + failed refresh.

```ts
const patch: OrgUnitPatch = {};
if (draft.name.trim() !== original.name) patch.name = draft.name.trim();
if (draft.parentId !== original.parentId) patch.parentId = draft.parentId;
```

- [ ] Add RED cases for stale detail GET, repeated submit, dirty close/route change and error preserving draft; implement cancellation with Subscription/DestroyRef and current target checks. Reuse `RecordNavigationDecision` for in-app navigation and native beforeunload warning for dirty/pending states.
- [ ] Add semantic RU keys and run `node scripts/sync-packaged-russian.mjs`, `node scripts/localization-audit.mjs`, focused unit tests, typecheck/build. Keep small panels/template/style files; review and commit task-owned clean files only.

## Task 4: User assignments and role-scope panels

**Files:** create `user-org-units-panel.component.ts/.html/.css/.spec.ts` and `role-scope-panel.component.ts/.html/.css/.spec.ts` plus `public-api.ts` under the feature directory; update scoped RU keys and packaged fallback.

**Interfaces:**
- User panel input `userId: number`, role panel input `roleId: number`; each exposes `canLeave(): boolean | Observable<boolean>`, `hasUnsavedWork(): boolean`, and a `busyChange` output for host target controls if needed.
- Only `public-api.ts` exports these two components; hosts do not import feature internals.
- Each panel owns read state, original snapshot, local draft, mutation target, inline error, discard confirmation and subscriptions.

- [ ] Write user panel RED with assigned IDs `[7]`, effective IDs `[7,8]`, legacy `9`: only 7 checked; legacy 9 read-only; save never copies 8/9 into the assignment set. Scope ALL/SELF with empty IDs shows semantic explanation, not denied state.

```ts
expect(panel.selectedOrgUnitIds()).toEqual([7]);
panel.save();
expect(api.saveAssignments).not.toHaveBeenCalled(); // unchanged draft
```

- [ ] Implement successful-read gate and explicit empty clear, view-only mode, inactive-node warning, read-only legacy context, isolated effective-scope refresh. A post-save read error cannot resubmit the completed PUT.
- [ ] Write role panel RED: no PUT on initial ALL fallback; no save before GET; `iam.org_units.assign` independent from `rbac.roles.grant`; changed rule requires confirmation, includes previous/new label and widest-rule explanation.
- [ ] Implement role panel against Task 3 API and typed ScopeRule options. Preserve existing matrix permissions and role selection lifecycle.
- [ ] Write stale-target, destroy, pending/double-submit, 403/409/error/retry/dirty cancel tests for both panels and prove RED before minimal fixes. Run focused unit/typecheck/i18n gates and commit new panels.

## Task 5: Route, menu and host integration

**Files:** modify `apps/web/src/app/app.routes.ts/.spec.ts`, `layout/app-shell/app-shell.component.ts/.spec.ts`, `features/iam/users/users.component.ts/.spec.ts`, `features/iam/roles/roles.component.ts/.spec.ts`.

**Interfaces:**
- Lazy route `/iam/org-units`: `permissionGuard('iam.org_units','view')`, `recordNavigationGuard`.
- Users/Roles implement `canLeaveRecordPage()` for the embedded panel and delegate before record selection/modal close. Current pending permission-matrix save still prevents role switching.
- Existing task/project/user record matchers and existing route URLs remain unchanged.

- [ ] Add RED route/menu test: view-only gets link and guarded page; absent view has neither link nor allowed navigation; changing another permission does not reveal it. Panel host selection must wait for a dirty decision and reject switching while pending.

```ts
expect(shell.canViewOrgUnits()).toBe(false);
permissions.setPermissions(['iam.org_units.view']);
expect(shell.canViewOrgUnits()).toBe(true);
```

- [ ] Add one menu item to IAM with proper active/aria-current/collapsed/mobile behavior; add feature route without changing the global registry.
- [ ] Embed panels for the selected existing user/role only with org view permission. Route navigation and host close/selection delegate to the panels; successful logout still bypasses draft prompts using existing record guard semantics.
- [ ] Test panel destruction and late callbacks, existing role matrix saves, deep-linked user detail, mobile drawer and keyboard navigation. Run full Angular/typecheck/i18n/build.
- [ ] Stage only task hunks in previously dirty shell files, clean host/routes files and tests. Review staged diff against preserved baseline; commit no unrelated prior UI edits.

## Task 6: Browser acceptance and final handoff

**Files:** create `e2e/tests/browser/instance/org-structure.spec.ts`; update this plan with exact evidence; update `docs/ai-context.md` only with verified final results, not audit guesses.

**Interfaces:** existing instance E2E auth/API setup and CSRF-aware request helpers. Isolated candidate origin/images identified explicitly before execution. New test seed uses a unique run prefix, one root if empty, synthetic users and roles only.

- [ ] Add browser test using exact user journey and observable HTTP outcomes:

```ts
await page.goto('/iam/org-units');
await page.getByRole('button', { name: 'Добавить подразделение', exact: true }).click();
await page.getByLabel('Код', { exact: true }).fill(`org-e2e-${Date.now()}`);
await page.getByLabel('Название', { exact: true }).fill('Тестовый отдел');
await page.getByRole('button', { name: 'Сохранить', exact: true }).click();
await expect(page.getByRole('button', { name: 'Выбрать подразделение Тестовый отдел', exact: true })).toBeVisible();
```

- [ ] Extend to assign employee, set UNITS/SUBTREE/SELF and inspect effective result, check real task read scope with a synthetic restricted actor. Test server conflict on occupied deletion and direct permission denial; UI-only route mocks are labelled separately.
- [ ] Run real API journey and controlled 503/pending UI cases; verify keyboard/discard behavior, 1366×900 and 390×844 in both themes with no page overflow or unexpected console/page errors.
- [ ] Run full Maven verify, Angular tests/typecheck/build/i18n, E2E typecheck/config/artifact-security and scoped browser suite, docs/hygiene/unified-boundary/whitespace checks. Record actual failures; do not label the whole package complete if any required acceptance fails.
- [ ] Run `graphify update .` after code changes. Keep dirty generated output out of task commits; regenerate from clean source only if publication is later requested.
- [ ] Review the complete scoped diff, run targeted corrections through RED/GREEN, record commit IDs and remaining limitations. Leave working installation untouched; report completion or a precise blocker without assuming push/deploy permission.

## Plan self-review

Spec coverage: §1–2 map to all tasks; §3 to Tasks 2/3; §4–5 to Tasks 1/4/5; §6–7 to Tasks 1/2; §8 to Tasks 3/4/5; §9–10 to Task 6 and scoped commit constraints. DTO names and shared TS types are defined above. Unknown business rules are not replaced with new defaults; existing ALL/widest/legacy semantics remain explicit. Code snippets show contract shape and test intent, not claims that unimplemented methods currently exist.

## Execution log

- Plan prepared after user requested implementation. Execution uses task-scoped implementers and independent reviews as required by the execution skill, with one implementer at a time. No production changes authorized.
- Initial frontend baseline: 47 files / 394 tests passed. Task 1 implementation is `8e43e19`: focused server contracts 44/44; root Maven verify 728/728 (723 server), no failures/errors/skips, finished 2026-09-08 16:47:17 +05. Independent task review is pending; these results do not mark the whole package complete.
