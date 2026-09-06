# Projects Interaction and E2E Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the three approved Projects follow-ups: truthful closed-task progress, comfortable keyboard/pointer interactions, and permanent browser regression coverage.

**Architecture:** Keep ProjectsComponent, the existing modal/button components, and the current REST contract. Change presentation and native form wiring, then test real browser journeys against an isolated local installation; controlled HTTP failures supplement real persistence checks. Do not redesign the page or change task statistics.

**Tech Stack:** Angular 22, TypeScript, Vitest, Playwright Chromium, existing Docker Compose server/web.

**Spec:** [Canonical technical specification](../../technical-specification.md), specifically FR-WORK-01, FR-I18N-01/04, NFR-SEC-03, AC-02/06/13. The user approved all three follow-ups on 2026-09-06; this plan records their bounded acceptance criteria, not new release requirements.

## Global Constraints

- Preserve `doneTasks`, `activeTasks`, `totalTasks`, their server calculations, task/project API payloads, row scope and action permissions.
- Project state values remain `A` and `P`; only their visible labels lose the technical codes.
- Russian remains the canonical source and fallback; all static UI copy uses localization keys. New semantic keys must not inherit stale completed-only translations or overrides.
- Preserve fresh detail loading, dirty-draft confirmations, single-flight mutation guards, sparse PATCH, inline errors, and existing global-toast ownership.
- Work on the user-requested `main` checkout; preserve unrelated audit drafts, graph changes, output and user data. Commit only owned files with DCO sign-off; no push.
- All mutating browser tests run on isolated `http://localhost:14200`, never the user's `http://localhost:4200`. Do not expose credentials, enable traces/video, or commit runtime evidence.
- No new dependencies, migrations, shared component redesign, provider configuration changes, or production-readiness claims.

## Ownership and verification commands

Projects template/styles/state live in `apps/web/src/app/features/tasks/projects/projects.component.ts`; DOM/unit contracts in its `.spec.ts`. Canonical copy lives in `apps/server/src/main/resources/i18n/ru.json`, generated fallback in `apps/web/src/app/core/i18n/packaged-russian.ts`. Native submit association belongs to `ui-button.component.ts` and its unit contract only if an external form attribute is needed. Permanent browser coverage belongs to `e2e/tests/browser/instance/projects-quality.spec.ts`.

Use existing Node `D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe` (called `$projectsNode` in shell examples), not a downloaded runtime. From `apps/web`:

```powershell
$projectsNode = 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe'
& $projectsNode node_modules/@angular/cli/bin/ng.js test --watch=false --include=src/app/features/tasks/projects/projects.component.spec.ts
& $projectsNode scripts/sync-packaged-russian.mjs
& $projectsNode scripts/localization-audit.mjs
& $projectsNode node_modules/typescript/bin/tsc --noEmit -p tsconfig.app.json
& $projectsNode node_modules/@angular/cli/bin/ng.js test --watch=false
```

The controller owns clean-source Docker builds, UI screenshots, whole-suite acceptance, Graphify update, local 4200 promotion, and documentation evidence. Implementers use focused tests during iteration and one full relevant suite before committing; they do not redeploy either runtime.

### Task 1: Truthful closed-task progress

**Files:**
- Modify: `apps/web/src/app/features/tasks/projects/projects.component.ts`
- Modify: `apps/server/src/main/resources/i18n/ru.json`
- Generate: `apps/web/src/app/core/i18n/packaged-russian.ts`
- Test: `apps/web/src/app/features/tasks/projects/projects.component.spec.ts`

**Interfaces:**
- Consumes `ProjectTaskStats { projectId, totalTasks, activeTasks, doneTasks }`; `doneTasks` counts `s.is_terminal = true` in MsTaskRepository, not successful completion only.
- Produces localized closed-task wording in table, cards and progress accessible names without changing numeric values or percentages.

- [x] **Step 1: Add a failing rendered contract.** Feed real ProjectsComponent a literal stats record `{ projectId: 1, totalTasks: 4, activeTasks: 2, doneTasks: 2 }`. Assert both list and card modes describe `2 / 4 закрыто`, progress has value `50` and an accessible name describing closed tasks for the project. Assert the rendered scope explanation includes terminal statuses including cancellation. Keep unknown/zero-stat tests passing.

```typescript
expect(host.querySelector('.progress-count')?.textContent?.trim()).toBe('2 / 4 закрыто');
expect(host.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('50');
expect(host.querySelector('[role="progressbar"]')?.getAttribute('aria-label')).toBe('Доля закрытых задач проекта Project 1');
fixture.componentInstance.viewMode = 'cards';
fixture.detectChanges();
expect(host.querySelector('.progress-count')?.textContent?.trim()).toBe('2 / 4 закрыто');
```

- [x] **Step 2: Run focused Projects tests and record the expected wording failure before implementation.** A missing test dependency or compile error is not the regression proof.
- [x] **Step 3: Wire new semantic copy, preserving calculations.** Use these canonical strings, retaining old keys for compatibility:

```json
{
  "projects.closed_ratio": "{done} / {total} закрыто",
  "projects.closed_progress_named": "Доля закрытых задач проекта {name}",
  "projects.closed_tasks": "Закрытие задач",
  "projects.closed_stats_scope": "Статистика учитывает только доступные вам задачи. Закрытые задачи — задачи в конечных статусах, включая выполненные и отменённые."
}
```

Replace the consumed `done_ratio`, `completed_ratio`, `progress_named`, `progress_zadach`, and `stats_scope` keys on Projects with these semantic keys where applicable. Do not rename the server DTO, add a completed-only metric, or change aggregation. New keys use RU fallback in untranslated languages rather than reviving old target wording.
- [x] **Step 4: Sync fallback, run focused tests, localization audit, typecheck and the full Angular suite.** Record commands/results and RED/GREEN evidence.
- [x] **Step 5: Self-review and commit only the four owned paths**, e.g. `git commit -s --only <paths> -m "fix(web): label terminal project tasks as closed"`.

### Task 2: Native form and action interaction contracts

**Files:**
- Modify: `apps/web/src/app/features/tasks/projects/projects.component.ts`
- Test: `apps/web/src/app/features/tasks/projects/projects.component.spec.ts`
- Modify/test: `apps/web/src/app/shared/ui/ui-button.component.ts` and `.spec.ts` for external native form association
- Corrective browser-discovered fix: `apps/web/src/app/shared/ui/ui-modal.component.ts` and `.spec.ts` for single-event Escape ownership
- Modify: `apps/server/src/main/resources/i18n/ru.json`
- Generate: `apps/web/src/app/core/i18n/packaged-russian.ts`

**Interfaces:**
- Consumes the existing `submitCreateProject()` / `submitEditProject()` guarded handlers and modal body/footer projection.
- Produces actual native forms with IDs `project-create-form` / `project-edit-form`, `(ngSubmit)` handlers and associated native submit buttons; optional `UiButtonComponent.form?: string` forwards to `[attr.form]` on its native button.
- Task 3 consumes these IDs only when necessary; visible labels and roles remain primary browser selectors.

- [x] **Step 1: Add failing component contracts for form submission.** Use real DOM form submit/click, not direct handler calls: create submission reaches one POST with entered values; edit reaches one sparse PATCH; a second submit while pending does not issue another mutation. Add the shared button test that a supplied form ID reaches the native button, while its default remains `type="button"` with no association. Preserve existing editor tests.

```typescript
const form = host.querySelector('#project-create-form') as HTMLFormElement;
expect(form).not.toBeNull();
form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
fixture.detectChanges();
expect(api.post).toHaveBeenCalledTimes(1);
```

- [x] **Step 2: Run the focused tests and observe actual missing-form/association failures.** Do not substitute keyboard events for native submission in unit DOM environments; real Enter is verified in Task 3.
- [x] **Step 3: Implement native form wiring with disabled fieldsets retained.** Add the smallest optional form association to the shared button, keeping existing consumers unchanged:

```typescript
@Input() form?: string;
// Native button template:
// [attr.form]="form || null"
```

```html
<form body id="project-create-form" (ngSubmit)="submitCreateProject()">
  <fieldset class="modal-form modal-form-fieldset project-create-form" [disabled]="isSubmitting()">
    <!-- Existing named fields and inline save error retained here. -->
  </fieldset>
</form>
<!-- Existing projected footer; remove duplicate onClick submission. -->
<ui-button type="submit" form="project-create-form" [loading]="isSubmitting()">{{ 'projects.sozdat_proekt' | t }}</ui-button>
```

Apply the same structure to the edit form, retaining `*ngIf="editingProject as p"`, named controls, required validation, dismissible/pending behavior and error UI. Do not install document-wide Enter handlers. Textarea Enter remains a newline. Angular's form handling should preserve current inline blank/whitespace validation.
- [x] **Step 4: Increase Projects-only compact action targets and clean state copy.** Keep local `.icon-ghost-btn` and `.edit-btn` at least `28px` wide/high, centered and non-shrinking; `.view-tasks-link` at least `28px` high. Preserve existing focus-visible styling or extend it to these controls using existing tokens. Do not increase every global button or change layout density. Use new `projects.state_active: "Активен"` and `projects.state_archived: "В архиве"` labels with unchanged option values `A`/`P`. Sync RU fallback.

```css
.icon-ghost-btn, .edit-btn { min-width: 28px; min-height: 28px; align-items: center; justify-content: center; flex-shrink: 0; }
.view-tasks-link { min-height: 28px; }
```

- [x] **Step 5: Run focused Projects/shared button suites, audit, typecheck and full Angular suite.** Report RED/GREEN and existing-test compatibility; real hitboxes, Enter, Tab/focus and mobile layout are controller/Task 3 browser checks.
- [x] **Step 6: Self-review and commit only owned files** with sign-off, e.g. `fix(web): support native project form submission`.

- [x] **Step 7: Correct nested Escape event ownership found during browser investigation.** The initial Chromium failure prompted investigation of document-level `UiModalComponent.onEscape`. Causal unit tests proved that one Escape could reach a newly opened confirmation or two existing stacked dialogs; consume a handled event before emitting close. Preserve topmost-only, non-dismissible, already-prevented, and expanded-combobox behavior. Do not add a delay, skip Escape in browser tests, change draft semantics, or redesign the modal. The repeated Chromium failure was separately traced to missing test synchronization: after canceling the first confirmation, await its hidden state before pressing Escape again. Explicit-boundary live probes passed both Create and Edit without another production change. Keep these two causes distinct in the evidence.

```typescript
const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
document.dispatchEvent(escape);
// Only the dialog owning Escape at dispatch may emit close;
// a newly opened discard dialog must remain open for a new user action.
expect(confirmationCloses).toBe(0);
```

Run focused UiModal and Projects suites (record RED/GREEN), then full Angular, typecheck and i18n audit. Append the fix evidence to the Task 2 report and commit only these corrective source/test changes with sign-off. The controller builds the new clean candidate; Task 3 browser tests with explicit UI transition boundaries must pass before acceptance.

### Task 3: Permanent Projects browser regression suite

**Files:**
- Create: `e2e/tests/browser/instance/projects-quality.spec.ts`
- Existing read-only helpers: `e2e/support/auth.ts`, `diagnostics.ts`, `playwright-security.ts`, `env.mjs`
- Modify `e2e/README.md` only if documenting the focused command meaningfully improves existing instructions; do not introduce another test config.

**Interfaces:**
- Consumes existing `loginToInstance(page)`, `collectPageErrors(page, allowedConsoleErrors?)`, `uniqueRunName(prefix)`, configured `instance` Playwright project, `/tasks/projects` page and current REST endpoints.
- Produces reproducible tests automatically included by the existing instance config. Relative `.js` helper imports; no absolute machine paths, credentials or standalone external configs in committed tests.

- [x] **Step 1: Write permanent regression cases with independent literal expectations.** The real-persistence journey creates by Enter in the name field, opens Edit through a visible row action, changes only name and observes a name-only PATCH, reopens to verify description preservation, then clears description and reopens to verify persisted empty text. Get IDs from observed detail request URLs if needed; avoid relying on Chromium response-body availability after dialog transitions.

```typescript
import { expect, test } from '@playwright/test';
import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';

// Before pressing Enter, register a response waiter scoped by method and URL.
const responsePromise = page.waitForResponse(response =>
  response.request().method() === 'POST' && response.url().endsWith('/api/v1/tasks/projects'));
await page.getByLabel('Название проекта', { exact: true }).press('Enter');
expect((await responsePromise).status()).toBe(201);
```

Verify the observed create status against the current controller contract before using the example status; do not alter production to fit a mistaken test assumption.
- [x] **Step 2: Cover draft safety and failure/retry.** For create and edit, Cancel/Escape on dirty fields opens confirmation, canceling that confirmation preserves the draft, confirming discard closes without mutation. Add narrowly routed controlled `503` save failures (separately labelled HTTP fixtures), assert inline error/draft preserved and one attempted request, then unroute and retry against the real server. Error allow-list must only cover the expected browser network `503` console message; page errors remain forbidden. Scope routes to the exact mutation endpoint and method and clean up in `finally`/test teardown.
- [x] **Step 3: Cover filters, view switching, keyboard and mobile.** Use uniquely named test projects or a deterministic read-only HTTP fixture for status/search/unknown stats. Verify search reset, A/P visible filters, list/card state, closed-task wording, and no page-level horizontal overflow at `390x844`. Measure table/card edit buttons and card task action >= `28px` in the relevant dimension. Verify native Enter saves once, textarea Enter inserts newline without a mutation, Tab stays in the open modal, and focus returns to the opener on close. Assert real rendered results, not framework internals or selector existence alone. Mobile must exercise an interaction, not just a screenshot.
- [x] **Step 4: Prove the regression detection and run focused suite.** Controller supplies an isolated candidate running Task 2 code. For already-fixed behavior, record a baseline RED run against the previous isolated image or a temporary isolated source build; missing test harness/config is not RED. Do not mutate production source in the shared checkout to fake a failure. Tests for established behavior can be characterization; document this distinction. Iterate the focused suite, then run E2E typecheck/config/artifact-security. Controller runs the whole browser suite once on the final candidate.

```powershell
# From e2e, after the controller securely supplies isolated environment:
& $projectsNode node_modules/typescript/bin/tsc --noEmit
& $projectsNode --test tests/config/*.test.mjs
& $projectsNode scripts/verify-artifact-security.mjs
# Existing Playwright config automatically discovers projects-quality.spec.ts.
```

- [x] **Step 5: Self-review and commit the permanent tests** with sign-off, e.g. `test(e2e): cover project editor and filter regressions`.

## Controller acceptance and handoff

- [ ] Task-scoped reviews, then one whole-change review; resolve important findings with covering tests.
- [ ] Build server/web from a clean archive of the final application commit. Validate isolated `localhost:14200`, run full Angular suite, app/e2e typechecks, i18n audit, Maven verify (catalog resource changed), full Playwright and artifact-security gates. Record precise results, not inferred production acceptance.
- [x] Inspect desktop and mobile screenshots outside the repository; identity/content/no-overlay/console/interaction checks all recorded.
- [x] Run `graphify update .` AST-only; keep dirty generated graph output out of commits because this checkout contains unpublished drafts.
- [ ] Tag rollback images, promote exactly the accepted server/web images to local `4200` without rebuilding, changing database volumes, or running migrations. Compare pre/post mounts and database/Typesense start timestamps. Verify health/public RU and read-only page load.
- [ ] Update `docs/ai-context.md` with actual accepted evidence, mark this plan complete and commit owned docs. No push; preserve unrelated dirty files.

## Acceptance checkpoint — implementation done, promotion held

Application commits: `96ac5d5`, `adcb531`, `e3e0627`; permanent E2E and scoped
diagnostics fix: `b958a58`, `4a5f764`. Task reviews and the scoped fixes are clean;
the whole-change reviewer found no implementation defect. Angular189/189,
Maven364/364, typechecks, i18n1034/1065, E2E config3/3 and artifact security pass.
All six Projects browser cases pass, including the tightened exact503 diagnostics.

Initial whole-browser run passed37/37, but the final run at `4a5f764` is36/37:
the existing mobile test fails on fully loaded `/analytics`. Read-only comparison
of the previous accepted54b6159 web and currente3e0627 web against the same
isolated backend/data reproduces identical `.page-content` client390/scroll499
and Analytics grid366/487. This is an unchanged Analytics defect, not a Projects
regression; the two-case comparison probe passed by asserting its presence on
both revisions. It does not turn the final failing acceptance test into GREEN.

No Analytics source edit or test skip is authorized by these three Projects
items. Scope decision is requested before fixing that separate screen and
resuming full acceptance. Local4200 stays on54b6159; only isolated14200 was
updated. Temporary comparison web14202 was stopped/removed, images retained.
Data services/volumes were not recreated; precheck verified48environment keys,
schema024/24successful migrations/no failed migration, and rollback images.
Docs record this checkpoint rather than declaring the package complete; no push.

Evidence outsideGit: `C:/Temp/smartupcms-projects-interaction-qa-20260906/`,
notably `accepted-final-browser-results`, `analytics-baseline-results`, and
`final-visual-results`. Remaining controller checkboxes are intentionally open.
