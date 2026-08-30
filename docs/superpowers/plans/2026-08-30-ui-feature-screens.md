# UI Feature Screens Hardening Plan

> Scope: deepen existing `web-instance` workflows without adding new product areas. Every change must preserve current API contracts and be verified by a failing component contract test before implementation.

## Goal

Finish the second UI/UX pass across authenticated feature screens after the shared UI foundation. Remove remaining click-only controls, connect labels and inputs, name icon actions, make tables and dense screens responsive, and expose recoverable loading/error states.

## Verification baseline

- App: `apps/web-instance`
- Test command: `npm test`
- Production build: `npm run build`
- Rendered QA server: `http://127.0.0.1:4210/`
- Authenticated browser actions must not use stored or visible credentials without action-time user confirmation; component tests cover authenticated DOM contracts when credentials are unavailable.

## Task 1 — Command palette interaction model

**Files**

- Modify: `apps/web-instance/src/app/layout/command-palette/command-palette.component.ts`
- Create: `apps/web-instance/src/app/layout/command-palette/command-palette.component.spec.ts`

**RED contracts**

- Dialog has a visible accessible name and `aria-modal`.
- Search is labelled as combobox and reports `aria-expanded`, `aria-controls`, and active option.
- Results use listbox/option semantics.
- Escape closes and restores focus to the element active before opening.

**Implementation**

- Replace generic dialog/result semantics with a stable combobox/listbox contract.
- Hide decorative icons from assistive technology.
- Preserve keyboard navigation and restore focus on close.
- Respect reduced motion through existing global tokens.

## Task 2 — Tasks and projects forms

**Files**

- Modify: `apps/web-instance/src/app/features/tasks/tasks.component.ts`
- Modify: `apps/web-instance/src/app/features/tasks/projects/projects.component.ts`
- Create: `apps/web-instance/src/app/features/tasks/tasks.component.spec.ts`
- Create: `apps/web-instance/src/app/features/tasks/projects/projects.component.spec.ts`

**RED contracts**

- Create/edit forms expose stable ids, explicit labels, native required state, and named Markdown/select controls.
- Icon-only actions have item-specific accessible names and explicit button types.
- Ancestor links and clickable identity text are native buttons/links.
- Table and board regions have names; mobile tables scroll inside their cards rather than the viewport.
- Modal save failures remain visible and recoverable.

**Implementation**

- Connect all create/edit task and project labels to native/shared controls.
- Pass `ariaLabel` into searchable/multi-select and Markdown controls.
- Replace click-only spans with buttons and hide decorative icons.
- Add responsive form stacking and table-scroll regions.

## Task 3 — Users and roles administration

**Files**

- Modify: `apps/web-instance/src/app/features/iam/users/users.component.ts`
- Modify: `apps/web-instance/src/app/features/iam/roles/roles.component.ts`
- Create: `apps/web-instance/src/app/features/iam/users/users.component.spec.ts`
- Create: `apps/web-instance/src/app/features/iam/roles/roles.component.spec.ts`

**RED contracts**

- User create/edit fields, filters, role checkboxes, and manager selectors are explicitly labelled.
- User identity and permission-module expanders are native controls.
- Filter/menu buttons expose expanded state and controlled region ids.
- All row actions have item-specific names.
- Role matrix groups permissions and exposes checkbox names without relying on visual position.

**Implementation**

- Add stable ids/for/name/autocomplete/required attributes.
- Convert clickable identity/module headers to native buttons.
- Add ARIA state for filter, module expansion, bulk selection, and active tabs.
- Preserve dense layout with responsive stacking and horizontal matrix scrolling.

## Task 4 — Audit and files operational screens

**Files**

- Modify: `apps/web-instance/src/app/features/audit/audit.component.ts`
- Modify: `apps/web-instance/src/app/features/files/files.component.ts`
- Create: `apps/web-instance/src/app/features/audit/audit.component.spec.ts`
- Create: `apps/web-instance/src/app/features/files/files.component.spec.ts`

**RED contracts**

- Search/filter controls have explicit labels.
- Tabs or segmented filters expose their selected state.
- Refresh/diff/download/delete actions have explicit types and record-specific accessible names.
- File names are native download buttons/links, not clickable divs.
- Tables are named and wrapped in focusable horizontal scroll regions.
- Loading/error/empty states are announced appropriately.

**Implementation**

- Apply the shared operational screen contract to both pages.
- Replace silent API error branches with inline status plus existing toast where appropriate.
- Keep all destructive confirmation behavior unchanged.

## Task 5 — Settings and profile forms

**Files**

- Modify: `apps/web-instance/src/app/features/settings/settings.component.ts`
- Modify: `apps/web-instance/src/app/features/iam/profile/profile.component.ts`
- Create: `apps/web-instance/src/app/features/settings/settings.component.spec.ts`
- Create: `apps/web-instance/src/app/features/iam/profile/profile.component.spec.ts`

**RED contracts**

- Every visible form control has an explicit label and stable id.
- Setting sections expose headings/group names and active tab state.
- Password fields expose correct autocomplete and inline validation relationships.
- Session/token actions use record-specific accessible names.
- Token creation and password change preserve user input on recoverable errors.

**Implementation**

- Add ids, labels, autocomplete, required, `aria-invalid`, and described-by relationships.
- Name tabs, switches, session and token actions.
- Add mobile form/tab wrapping without changing settings keys or API payloads.

## Task 6 — Cross-screen regression scan

**Files**

- Modify only validated remaining files from `apps/web-instance/src/app/features/**`.

**Checks**

- No non-semantic `(click)` remains unless it only stops propagation or handles a backdrop.
- No native button omits `type` inside a form-capable component.
- No icon-only control relies on `title` alone.
- No form label is visually present but programmatically disconnected.
- No component removes the global focus-visible outline.
- All dense tables have a bounded responsive overflow strategy.

## Task 7 — Final verification and audit evidence

**Files**

- Create: `audit/widgets-2026-08-30.md`
- Update: `graphify-out/**` via `graphify update .`

**Commands and evidence**

- Run all `web-instance` tests and production build.
- Run all `web-cp` tests/build with bundled Node 24.19.0.
- Re-run static UI contract scans.
- Run browser QA on unauthenticated desktop/mobile routes without transmitting credentials.
- Document authenticated browser coverage limitation and component-test evidence.
- Update Graphify only after all code is final, then commit audit and graph changes separately.
