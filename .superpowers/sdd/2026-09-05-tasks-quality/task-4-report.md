# Task 4 report — accessible task interactions, search and compact UI

## Status and scope

- Status: implemented and locally verified on `main` from base `b96f2c32a67cad9c6aa5d582307da3ef92d6f29a`.
- Intended commit: `fix(tasks): polish accessible task interactions`.
- Scope is frontend behavior/styles, the existing shared modal/selectors, tests, and Russian localization only. The report/export endpoint remains the existing actor-scoped, format-only endpoint. No backend behavior, database color, migration, dependency, branch, push, deployment, live port 4200, Graphify output, audit draft, or root-owned plan was changed by this task.
- Task 2 fresh edit/detail state, local date precision helpers, per-task comment drafts, dirty confirmations and pending fieldsets remain covered by the full suite. Task 3 cursor ownership, exact failed-attempt retry, separate 300 ms selector lookups, retained identities and unchanged-assignment PATCH omission remain covered as well.

## RED evidence

Initial command:

```powershell
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe' node_modules/@angular/cli/bin/ng.js test --watch=false --include='src/app/features/tasks/tasks.component.spec.ts'
```

Observed: 1 file failed, 11 intended failures and 32 passes. The failures showed that rows still had button semantics, a nested edit key opened detail, task status lacked a separate semantic-text/custom-dot structure, export scope copy was absent, visible business labels were technical, selector Escape did not leave the editor open, detail status/comment names were absent, 350 ms search did not run, an old list response was accepted during debounce, empty recovery actions were absent, and unauthorized cards retained drag affordance.

The selector/modal/type compilation initially failed after adding an event parameter because Angular typed the host-listener event as `Event`, not `KeyboardEvent`. That was a fixture/typing error rather than behavioral RED; changing only the handler parameter type exposed the intended failures.

Follow-up search ownership RED used the same command: 1 intended failure and 42 passes. A filter change during the pending 350 ms search produced a second delayed request. The list loader now owns cancellation of that timer, while debounce immediately invalidates the old request, error/retry state and pagination affordance.

Follow-up kanban empty-state RED used the same command: 1 intended failure and 43 passes because the filtered kanban view had no reset recovery. Follow-up control-label RED confirmed that the responsible selector retained its old accessible name. A drag regression first used `DragEvent`, which the configured test DOM did not provide; this constructor error was corrected to a bubbling/cancelable `Event` before counting behavioral evidence.

## Implemented behavior

- Table rows retain native row semantics. Each title is a native `button` named `Открыть задачу #ID: title`; kanban titles use the same stable accessible task-open name. Safe container clicks ignore interactive descendants. Removing synthetic row/card keyboard handlers prevents Enter/Space on table status/edit and kanban move controls from opening detail. Opening edit closes matching detail and detail cannot open over edit, so only one detail/edit dialog is active.
- Both shared selectors keep their existing document Escape handling and local-search defaults. An open selector consumes Escape and restores trigger focus; `UiModal` ignores Escape while an expanded popup is inside it and still applies its topmost-modal guard.
- Users without task-update permission receive disabled CDK drop/drag behavior, no HTML draggable attribute, no grip or move actions, and guarded native/CDK handlers.
- Custom status colors are now limited to separate dots or column borders in filter tabs, table rows, kanban columns, subtasks, detail, and dictionaries. Status names/selects use semantic text colors; database values are unchanged.
- Top-level search invalidates the current list immediately, waits exactly 350 ms, resets cursor state, and ignores canceled answers. Enter applies immediately and cancels the delayed duplicate. Clear/reset/filter application uses the same single list loader; old Retry and pagination cannot reuse a prior cursor during debounce. Remote selectors remain independently debounced at 300 ms.
- Empty table and kanban results offer `Сбросить все фильтры`; first-empty views offer the existing `Новая задача` action when authorized.
- Export UI explicitly says `Экспорт всех доступных задач` and explains `Текущие фильтры не применяются`; XLSX/CSV still call only `/api/v1/reports/tasks/export?format=...`.
- Visible and accessible form labels are now `Ответственный`, `Описание`, and `Динамические поля`. Task 5 browser locators must replace `Ответственный сотрудник` with `Ответственный`; the markdown editor's associated label is now `Описание`. The stable task-open accessible name is unchanged.
- Mobile rules prioritize the New Task control, compact secondary header actions and spacing, and prevent short status/filter labels from wrapping. Existing semantic light/dark surfaces and local table scrolling remain intact.

## GREEN and verification evidence

Focused command (Tasks, modal, both selectors):

```powershell
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe' node_modules/@angular/cli/bin/ng.js test --watch=false --include='src/app/features/tasks/tasks.component.spec.ts' --include='src/app/shared/ui/ui-modal.component.spec.ts' --include='src/app/shared/ui/ui-searchable-select.component.spec.ts' --include='src/app/shared/ui/ui-user-multi-select.component.spec.ts'
```

Result: 4 files, 54/54 tests passed.

Full frontend (run once after the main implementation): `ng test --watch=false` through pinned Node 24.15.0 — 32 files, 156/156 tests passed. Subsequent small accessibility-label/kanban-recovery refinements were verified by the focused 54/54 suite, typecheck, and production build rather than rerunning the full suite.

Typecheck: pinned Node running `node_modules/typescript/bin/tsc --noEmit -p tsconfig.app.json` — exit 0, no diagnostics.

Production build: pinned Node running `node_modules/@angular/cli/bin/ng.js build --configuration production` — exit 0; output `apps/web/dist/web`. An earlier `npm run build` attempt launched host Node 24.14.0 and was rejected by Angular before compilation; the direct pinned-Node invocation is the valid build evidence.

Localization:

```powershell
$env:Path='D:\Claude\dwh\output\tools\node-v24.15.0-win-x64;' + $env:Path
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/npm.cmd' run i18n:sync-ru
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/npm.cmd' run i18n:audit
```

Result: 1,039 fallback strings synchronized; audit passed with 1,019 referenced / 1,039 catalog keys. Scoped `git diff --check` found no whitespace errors; only expected LF-to-CRLF notices were emitted.

## Exact task files

- `apps/web/src/app/features/tasks/tasks.component.ts`
- `apps/web/src/app/features/tasks/tasks.component.spec.ts`
- `apps/web/src/app/shared/ui/ui-modal.component.ts`
- `apps/web/src/app/shared/ui/ui-searchable-select.component.ts`
- `apps/web/src/app/shared/ui/ui-user-multi-select.component.ts`
- `apps/server/src/main/resources/i18n/ru.json`
- `apps/web/src/app/core/i18n/packaged-russian.ts`
- `.superpowers/sdd/2026-09-05-tasks-quality/task-4-report.md`

## Concerns and handoff

- No live browser/E2E QA was run or claimed. The controller owns isolated candidate browser verification.
- Compact/mobile and light/dark appearance is protected by semantic CSS and DOM contracts but still benefits from the controller's visual browser pass.
- The export menu intentionally describes server scope rather than applying current client filters; this matches the unchanged endpoint contract.
- Graphify was intentionally not updated. Existing dirty Graphify, plan, audit and output files remain unrelated and excluded from the exact task commit.
