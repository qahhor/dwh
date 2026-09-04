# SmartupCMS UI Theme Consistency Design

**Status:** Approved on 2026-09-04

**Visual source of truth:** Superdesign draft `1874830f-dfbc-43cd-8939-e78e0143ae09`, version 2

## Goal

Remove accidental dark content surfaces from the light theme without changing
SmartupCMS information architecture, business behavior, copy, density, or the
intentionally dark persistent sidebar.

## Scope

The verified defects are limited to these authenticated routes and components:

- `/files` — storage metrics, filters, file table, metadata and empty states;
- `/audit` — tabs, filters, audit/security tables and detail surfaces;
- `/settings` — settings cards, labels, inputs, selects, toggles and dividers;
- `/iam/custom-fields` — filters, table, badges/actions and create/edit modal fields.
- `/tasks` — shared searchable-select overlays, drag preview shadow, and the
  empty custom-field hint accent.

Other routes were inspected in the light theme and are not redesign targets.

## Design rules

1. `apps/web/src/styles.css` remains the canonical source of theme tokens.
2. Content surfaces use `--bg-surface`; contextual neutral fills use
   `--bg-hover` or `--bg-active`.
3. Borders use `--border-color` or `--border-subtle`.
4. Text uses `--text-main`, `--text-muted`, or `--text-light`; inverse text is
   reserved for genuinely dark/brand surfaces.
5. Brand and focus states use `--primary`, `--primary-hover`,
   `--primary-subtle`, `--primary-text`, and `--focus-ring`.
6. The legacy undefined names `--bg-card`, `--color-primary`,
   `--text-primary`, and `--text-secondary` are removed from the affected
   components rather than added as aliases.
7. Direct dark-theme fallbacks such as `#1e293b`, `#f1f5f9`, and
   `rgba(255, 255, 255, ...)` are removed from ordinary content surfaces.
8. Semantic status colors may retain the existing success/warning/danger/info
   tokens. Material Symbols and existing assets are unchanged.
9. Floating overlays and drag previews use the canonical `--shadow-overlay`;
   the undefined `--shadow-lg` and fallback-only `--color-primary` are removed.
10. Desktop structure stays pixel-stable relative to the approved draft; the
   existing responsive layout must not gain overlap, clipping, or horizontal
   page overflow.

## Acceptance criteria

- Under `data-theme="light"`, the primary surfaces and form controls on all
  listed routes resolve to the light semantic palette and remain readable.
- Under `data-theme="dark"`, the same surfaces resolve to the canonical dark
  semantic palette rather than light values or obsolete fallback colors.
- The custom-field create modal follows the active theme.
- Searchable task controls render a visible canonical overlay shadow, and the
  empty custom-field hint uses the canonical primary color.
- The theme-toggle control changes the rendered theme without a console error.
- Existing Angular unit tests, type checking, production build, and the new
  browser regression test pass.
- Browser QA covers desktop and mobile-sized viewports and records no relevant
  framework overlay, clipping, or console/page error.

## Non-goals

- No new route, feature, component library, token namespace, or visual style.
- No changes to backend APIs, RBAC, persistence, content hierarchy, or copy.
- No modification of unrelated dirty files or local audit drafts.
