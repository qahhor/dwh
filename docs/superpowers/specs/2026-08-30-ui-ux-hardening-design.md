# UI/UX hardening for DWH web applications

**Date:** 2026-08-30  
**Status:** Approved direction; awaiting specification review  
**Scope:** `apps/web-instance` and `apps/web-cp`

## 1. Objective

Deepen the existing product by making every current interface element reliable,
consistent, accessible, and predictable. The work must not add product features
or redesign the information architecture. It strengthens the UI foundation and
then applies the same contracts to every existing screen.

The result is accepted when the existing user flows work consistently on desktop
and tablet, all shared primitives satisfy their behavioral contracts, critical
accessibility defects are removed, UI failures offer recovery, and automated plus
rendered checks protect the changes from regression.

## 2. Product and viewport boundaries

The applications remain desktop-first:

- Full acceptance at 1280 px and 1024 px widths.
- Functional tablet acceptance at 768 px.
- A 390 px smoke check guarantees that content remains reachable and controls do
  not overlap or become unusable; it does not introduce bottom navigation,
  gestures, or a separate mobile information architecture.
- Light theme is the primary acceptance theme. Dark theme is checked for token
  regressions on shared primitives and the application shell.

This resolves the conflict between sections 1 and 5.6 of `TRD-02-uiux.md` in
favor of the approved desktop-first product boundary.

## 3. Current-state findings that define the work

The audit found the following systemic defects:

1. Both SPAs lack an effective frontend regression suite. `web-instance` exposes
   `ng test` without a configured test target; `web-cp` has no test script.
2. Form labels are visually present but not programmatically associated with
   controls across the applications.
3. Shared buttons remove the browser focus outline without providing a visible
   `:focus-visible` replacement.
4. The modal supports Escape but has no labelled dialog contract, initial focus,
   focus trap, focus restoration, or background scroll management.
5. Toasts are neither live regions nor alerts, and an entire toast is a clickable
   non-semantic container.
6. Icon-only controls frequently rely on `title` instead of accessible names.
7. Some clickable `div` and anchor elements are unreachable or ambiguous from
   the keyboard.
8. The login screen defaults the login field to `admin`, uses placeholder text as
   the effective accessible name, and renders password recovery as an anchor
   without `href`.
9. `ui-button` host sizing does not make the internal button full width, visible
   on the primary login action.
10. Feature components mix shared and native controls, contain many hard-coded
    measurements and colors, and implement repeated states inconsistently.
11. Several request handlers silently swallow errors, leaving stale or empty UI
    without a retry path.
12. Main screens are very large standalone components, including a 2,900-line
    task screen. This makes UI behavior hard to test and encourages duplication.
13. The accepted ADR requires shared wrappers and a `/ui-kit` acceptance surface,
    but the implementation does not yet meet that contract.
14. `web-cp` does not build with the currently selected Node runtime because its
    installed Angular CLI rejects Node 24.14. The environment/runtime contract
    must be made reproducible before its UI can be accepted.

These findings are not an invitation to redesign. They define the defects that
the hardening work must remove.

## 4. Chosen approach

Use a foundation-first, screen-by-screen migration.

### 4.1. Why foundation-first

Fixing shared behavior once prevents repeated patches in every feature. It also
creates testable contracts before large screens are touched. Screen-by-screen
cosmetic patches were rejected because they would preserve duplicated behavior
and make regressions likely. A full redesign was rejected because it expands the
project rather than deepening it.

### 4.2. Delivery sequence

1. Establish a native Angular component-test target for both SPAs and add the
   smallest browser-oriented smoke layer needed for rendered validation.
2. Harden global tokens, focus behavior, reduced-motion behavior, and shared UI
   primitives.
3. Fix the unauthenticated entry surfaces: instance login, OTP, password reset,
   and control-plane login.
4. Fix application shells and navigation behavior.
5. Migrate feature screens in user-value order:
   tasks and projects; users, roles, and profile; files and notifications; audit,
   custom fields, and settings; control-plane fleet, clients, backups, and
   announcements.
6. Run the complete automated suite, production builds, and rendered QA at the
   accepted viewports.

Each behavior change follows RED -> GREEN -> REFACTOR. A failing test must
demonstrate the defect before production code changes.

## 5. UI foundation contracts

### 5.1. Global tokens and focus

- Existing semantic color tokens remain the source of truth.
- Missing tokens needed by multiple components may be added for focus rings,
  control heights, spacing, and destructive hover states.
- New component styles must not introduce direct semantic colors.
- Every interactive element must expose a visible `:focus-visible` state with a
  minimum two-pixel-equivalent indicator and sufficient contrast.
- Motion that is not essential must be disabled under
  `prefers-reduced-motion: reduce`.
- Global typography and native form controls must inherit the selected theme.

This phase does not mechanically replace every historical pixel value. Values
are tokenized when they represent a repeated design decision or when the touched
component is being brought into compliance.

### 5.2. Buttons

`ui-button` becomes the contract for application actions:

- Correct native `type`, disabled state, and click behavior.
- `aria-busy` and a non-visual loading announcement when loading.
- Optional accessible label for icon-only usage.
- Explicit full-width mode so host sizing and internal button sizing agree.
- Visible focus, minimum usable target size, and tokenized destructive hover.
- No duplicate event emission on form submission.

Native buttons remain valid for tightly scoped icon controls only when they have
an accessible name and use the same focus and target-size contract.

### 5.3. Forms

- Every control receives a stable `id`; every visible label uses `for`.
- Required, invalid, and described-by relationships are exposed through ARIA.
- Validation appears inline after blur and as a concise summary after submit on
  multi-field forms.
- Server problem details are translated into field errors when a field is known;
  otherwise the form shows a recoverable inline error.
- Loading disables duplicate submission but does not erase entered values.
- Placeholder text is an example, never the only accessible name.
- Sensitive fields are empty by default and use correct autocomplete semantics.

### 5.4. Modal dialogs

`ui-modal` provides one dialog behavior for the main SPA:

- `aria-labelledby` points to a unique visible title.
- Initial focus moves to the first intended control or the close button.
- Tab and Shift+Tab remain inside the open dialog.
- Escape and backdrop dismissal are supported when dismissal is allowed.
- Focus returns to the invoking control after close.
- Background scrolling is suppressed while open.
- Tablet dialogs fit the viewport without losing header or footer actions.
- Destructive confirmations name the affected object; irreversible operations
  require explicit object-name confirmation where the current flow requires it.

No new dialog library is introduced. The existing wrapper is hardened in place.

### 5.5. Toasts and status feedback

- The container is an `aria-live` region.
- Errors use assertive announcement; success and informational messages use
  polite announcement.
- Dismissal is a labelled button. The toast body is not a generic click target.
- Focus is not stolen when a toast appears.
- Long content wraps without covering primary actions at 768 px or 390 px.
- Request errors are never silently swallowed when the user needs to act.

### 5.6. Complex shared controls

Searchable select, user multi-select, pagination, markdown editor, file upload,
and custom fields receive explicit keyboard and accessible-name contracts:

- Trigger, expanded state, selected value, and owned popup are programmatically
  exposed.
- Arrow keys, Enter, Escape, and Tab have predictable behavior where applicable.
- Clickable file rows become links or buttons and remain operable by keyboard.
- Remove, clear, previous, next, and page controls have accessible names.
- Upload exposes progress and error state; failed items can be retried.
- Disabled and read-only states are visually and semantically distinct.

## 6. Application-shell contracts

- Add a skip link to main content.
- Icon controls expose accessible names independent of `title`.
- Collapsed navigation remains understandable through labels/tooltips.
- The current route is exposed with `aria-current`.
- Notification counts have text equivalents and are not color-only.
- At widths below 1024 px the sidebar uses the existing compact mode without
  obscuring content. At 768 px content remains reachable and tables scroll inside
  their own region.
- Global search, language, theme, notifications, profile, and logout retain a
  stable keyboard order.
- Connection and announcement states are announced without blocking work.

The work does not create new navigation destinations.

## 7. Screen-level migration contract

Every routed screen is reviewed against the same checklist:

1. Heading and landmark hierarchy.
2. Keyboard reachability and visible focus.
3. Accessible names and form relationships.
4. Loading, success/data, empty, error, and permission-denied states.
5. Retry or recovery for recoverable errors.
6. Empty-state explanation plus the existing primary action when authorized.
7. Consistent button and destructive-action behavior.
8. Table containment and readable layout at 1280, 1024, and 768 px.
9. No clipping, overlap, scroll trap, stale loading, or inaccessible popup.
10. Token and shared-component conformance in touched code.
11. Locale-safe date, number, and user-facing text handling where the existing
    i18n service already provides the contract.
12. No material bundle regression from the hardening work.

Large screen components may be split only along existing UI boundaries when the
split is required to test or stabilize behavior. No domain logic or API contract
is redesigned as part of this work.

## 8. Error handling and data states

- Page-level initial-load failures render an inline error with retry.
- Background refresh failures preserve current data and show a non-blocking
  status message.
- Mutation failures preserve the form or local edit and identify the failed
  action.
- Permission errors render an explicit unavailable state instead of a blank
  panel or unexplained redirect.
- Empty data and empty filtered results use different messages; filtered empty
  states offer filter reset.
- Skeletons are used for initial page content; spinners remain inside action
  controls.

The API contract is not changed. Existing problem responses are consumed more
consistently by the UI.

## 9. Testing design

### 9.1. Component tests

Tests target user-observable behavior rather than template source text:

- Keyboard focus and activation.
- Accessible role, name, state, and live-region behavior.
- Loading, disabled, error, and retry transitions.
- Modal focus trap and restoration.
- Form label/error relationships and submit protection.
- Responsive state helpers when component behavior changes by breakpoint.

Tests use real components. HTTP is replaced only at the request boundary with
complete response fixtures.

### 9.2. Route smoke tests

The key paths are:

- `/login`: credentials -> OTP or recoverable error -> expected feedback.
- Password-reset dialog: open -> keyboard navigation -> submit state.
- Authenticated shell: route load -> navigation/control activation -> rendered
  target screen.
- Representative list: initial load -> filter/empty/error/retry -> data state.
- Representative mutation: open form -> validation -> submit -> success/error.
- Control-plane login and one operational dashboard route.

### 9.3. Rendered QA

The in-app Browser is the primary rendered-validation surface. For each changed
flow it verifies page identity, non-blank content, absence of framework overlays,
console health, screenshot evidence, and at least one real interaction followed
by a state assertion.

Screenshots cover 1280, 1024, 768, and a 390 px smoke viewport where the change
can affect layout. Light theme is complete; dark theme is checked on shared
primitives and the shell.

### 9.4. Build and quality gates

- Both production builds pass under a documented supported Node version.
- All component and route tests pass without warnings caused by the changes.
- Key rendered flows have no relevant console errors or framework overlays.
- Critical accessibility violations on login and the main task surface are zero.
- Initial and component-style budgets do not regress beyond existing limits.

## 10. Audit artifacts and traceability

The implementation produces `audit/widgets-2026-08-30.md` with:

- UI maturity score and top systemic defects.
- Coverage matrix for every routed module in both SPAs.
- Validated findings with severity, fix, risk, and test evidence.
- Accessibility, responsive, state, i18n, performance, and consistency scorecards.
- Remaining medium/high-risk items that require a separate architectural change.

Low-risk fixes are implemented directly. The report does not create speculative
backlog items unrelated to the audited interfaces.

## 11. Non-goals

- New product modules, routes, workflows, or backend APIs.
- A visual rebrand or marketing redesign.
- Full phone-specific navigation, swipe gestures, pull-to-refresh, or bottom
  sheets.
- Replacing Angular, the router, state model, or API services.
- Mechanical refactoring of unrelated business logic.
- Full i18n migration of all historical strings unless required to fix a touched
  interaction.

## 12. Acceptance criteria

The hardening work is complete when:

1. Every existing route in both SPAs appears in the audit coverage matrix.
2. All validated critical and high-severity UI/UX defects in the accepted scope
   are fixed or documented with a concrete external blocker.
3. Shared primitives satisfy the contracts in section 5 with automated tests.
4. Login, password reset, shell navigation, representative list/error/retry, and
   representative mutation flows pass rendered QA.
5. All forms touched by the migration have associated labels and observable
   validation.
6. All icon-only and non-native interactive controls touched by the migration
   are keyboard-operable and accessibly named.
7. Both SPAs build reproducibly and all introduced tests pass.
8. Accepted viewports contain no blocking overlap, clipping, or scroll traps.
9. The Graphify graph is updated after code changes.
10. No new product feature or mobile-only interaction has been introduced.

