# UI Theme Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the verified SmartupCMS content areas render consistently in light and dark themes without changing their structure or behavior.

**Architecture:** Keep `styles.css` as the semantic token authority and replace component-local dark fallbacks with those existing tokens. Protect the rendered contract with one real-browser Playwright test that exercises both themes and the custom-field modal.

**Tech Stack:** Angular 22, component-scoped CSS, Vitest, Playwright 1.62, Docker Compose

**Spec:** `docs/superpowers/specs/2026-09-04-ui-theme-consistency-design.md`

## Global Constraints

- Preserve all existing routes, DOM semantics, copy, interactions, and the intentionally dark sidebar.
- Do not introduce a second token namespace or add aliases for obsolete tokens.
- Do not modify `apps/web/angular.json`, `apps/web/src/index.html`, `e2e/tests/browser/instance/auth.spec.ts`, `apps/web/src/favicon.svg`, or local `audit/` drafts.
- Do not commit, push, or deploy unless the user explicitly requests it.

---

### Task 1: Browser regression contract

**Files:**
- Create: `e2e/tests/browser/instance/theme-consistency.spec.ts`

**Interfaces:**
- Consumes: `loginToInstance(page)` from `e2e/support/auth.ts`; theme toggle with accessible name `Переключить тему`.
- Produces: a browser-level contract for computed backgrounds, foregrounds, borders, modal controls, and theme switching.

- [x] **Step 1: Write the failing real-browser test**

Create a table-driven Playwright test that logs in, forces light mode through
the visible toggle when necessary, visits `/files`, `/audit`, `/settings`, and
`/iam/custom-fields`, and asserts the computed colors of the route-specific
surface selectors. Open the create custom-field modal and assert its form field
surface and text. Open both searchable task controls and assert their overlay
shadow. Toggle dark mode and assert the same route surfaces use the canonical
dark palette.

- [x] **Step 2: Run the test to verify RED**

Run:

```powershell
Push-Location e2e; npm run test:instance -- theme-consistency.spec.ts; Pop-Location
```

Expected: FAIL on the current light theme because one of the target surfaces
resolves to a dark fallback such as `rgb(30, 41, 59)` instead of the canonical
light surface `rgb(255, 255, 255)`.

- [x] **Step 3: Keep the test focused on user-visible behavior**

The test must use `getComputedStyle` in the actual browser. It must not grep
source code, assert implementation-only class text, expose credentials, or
mock Angular/theme services.

### Task 2: Semantic token repair

**Files:**
- Modify: `apps/web/src/app/features/files/files.component.ts:342-790`
- Modify: `apps/web/src/app/features/audit/audit.component.ts:465-960`
- Modify: `apps/web/src/app/features/settings/settings.component.ts:453-721`
- Modify: `apps/web/src/app/features/iam/custom-fields/custom-fields.component.ts:235-505`
- Modify: `apps/web/src/app/features/tasks/tasks.component.ts`
- Modify: `apps/web/src/app/shared/ui/ui-searchable-select.component.ts`
- Modify: `apps/web/src/app/shared/ui/ui-user-multi-select.component.ts`

**Interfaces:**
- Consumes: canonical theme tokens from `apps/web/src/styles.css:1-81`.
- Produces: identical component DOM and behavior with theme-aware scoped CSS.

- [x] **Step 1: Replace obsolete component tokens**

Map `--bg-card` to `--bg-surface`, `--text-primary` to `--text-main`,
`--text-secondary` to the appropriate `--text-muted`/`--text-light`, and
`--color-primary` to `--primary` or `--primary-text` according to whether the
value is an interactive fill or readable foreground.

- [x] **Step 2: Replace dark-only local fills and borders**

Use `--bg-surface`, `--bg-hover`, `--bg-active`, `--border-color`, and
`--border-subtle` for cards, toolbars, rows, inputs, selects, toggles, and
dividers. Preserve existing semantic status tokens and layout declarations.

- [x] **Step 3: Replace dark-only local text and focus colors**

Use `--text-main`, `--text-muted`, `--text-light`, `--primary-text`, and
`--focus-ring`. Native `option` elements must inherit the active semantic
surface and text colors.

Replace the remaining undefined `--shadow-lg` references with
`--shadow-overlay` and the fallback-only `--color-primary` reference with
`--primary`.

- [x] **Step 4: Run the targeted Angular tests**

Run:

```powershell
Push-Location apps/web; npm test -- --include='src/app/features/{files,audit,settings,iam/custom-fields}/*.component.spec.ts'; Pop-Location
```

If the Angular runner does not accept the glob, run the complete `npm test`
gate instead. Expected: all selected tests PASS with zero failures.

- [x] **Step 5: Run the browser contract to verify GREEN**

Rebuild the Docker web service from the changed source, reload the existing
stack without deleting volumes, then run:

```powershell
Push-Location e2e; npm run test:instance -- theme-consistency.spec.ts; Pop-Location
```

Expected: PASS in both light and dark assertions.

### Task 3: Full validation and design QA

**Files:**
- Create: `design-qa.md`
- Update after source changes: `graphify-out/` through `graphify update .`

**Interfaces:**
- Consumes: approved Superdesign version 2 and the running local application.
- Produces: fresh build/test evidence and a visual comparison report with `final result: passed` or `blocked`.

- [x] **Step 1: Run frontend gates**

```powershell
Push-Location apps/web; npm test; npm run typecheck; npm run build; Pop-Location
Push-Location e2e; npm run typecheck; npm run test:artifact-security; Pop-Location
```

- [x] **Step 2: Validate the rendered flow**

The flow under test is: authenticated app → light theme → affected routes,
custom-field modal, and task overlays render the canonical tokens → theme
toggle → the same route surfaces render the dark palette without runtime errors.

Check desktop and one mobile-sized viewport for page identity, meaningful DOM,
framework overlays, console/page errors, clipping, overlap, focus visibility,
and at least one real interaction.

- [x] **Step 3: Compare against the approved visual target**

Capture the implementation at the same desktop state as Superdesign version 2,
combine the reference and implementation into one comparison image, and record
typography, spacing, tokens, assets/icons, and copy in `design-qa.md`. Fix every
P0/P1/P2 mismatch and repeat the comparison until the final result is passed.

- [x] **Step 4: Refresh the knowledge graph**

Run `graphify update .`. If generated graph outputs are to be committed, repeat
that generation from a clean checkout of the intended source commit.

- [x] **Step 5: Review scope and working tree**

Run `git diff --check`, inspect the scoped diff, and verify that unrelated dirty
files remain unchanged. A commit is intentionally omitted from this execution
because it was not requested and the checkout already contains unrelated work.
