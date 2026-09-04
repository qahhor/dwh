# SmartupCMS Theme Repair — Design QA

## Comparison target

- Source visual truth: `C:\Temp\smartupcms-ui-qa-2026-09-04\settings-reference.png`
- Source draft: Superdesign `1874830f-dfbc-43cd-8939-e78e0143ae09`, version 2
- Implementation: `D:\Claude\dwh\e2e\test-results\instance-theme-consistency-edbe3-face-and-form-control-light-instance\settings-light.png`
- Dark-state evidence: `D:\Claude\dwh\e2e\test-results\instance-theme-consistency-13a7e-s-across-the-affected-pages-instance\settings-dark.png`
- Viewport and CSS size: 1440 × 900 px
- Source pixels: 1440 × 900; implementation pixels: 1440 × 900
- Device scale factor: 1 for both captures; no density normalization required
- State: authenticated administrator, Russian locale, `/settings`, General tab, light theme

The two original-resolution screenshots were opened together in one comparison
input. The full view keeps the form, type, dividers, icons, and control chrome
legible, so a separate focused crop was not needed.

## Findings

No actionable P0, P1, or P2 mismatch remains within the approved systemic
theme-repair scope.

- Fonts and typography: Inter/system fallback, weights, hierarchy, wrapping,
  and antialiasing remain the production values. The longer production page
  title is intentionally preserved; changing application copy was a non-goal.
- Spacing and layout rhythm: the existing 1000 px settings content width,
  two-column form, card padding, dividers, tab density, radii, and action
  placement remain stable. The reference's simplified shell is not copied
  because the approved direction explicitly preserves the current application
  shell and information architecture.
- Colors and visual tokens: the light card and controls resolve to
  `rgb(255, 255, 255)`, borders to `rgb(226, 232, 240)`, and primary text to
  `rgb(15, 23, 42)`. The dark card resolves to `rgb(19, 27, 46)`, borders to
  `rgb(30, 41, 59)`, and text to `rgb(241, 245, 249)`.
- Image quality and asset fidelity: the existing vector-style SmartupCMS mark
  and Material Symbols remain sharp; no placeholder, CSS illustration, or new
  raster asset was introduced by this repair.
- Copy and content: application-specific Russian copy and server-provided form
  values remain authoritative. Empty select values in the captured environment
  are runtime data, not a visual substitution.
- Icons and interactions: Material Symbols remain aligned. Theme toggle,
  settings tabs, the custom-field modal, both task searchable-select overlays,
  and the mobile navigation drawer were exercised without relevant console or
  page errors. Both task overlays resolve a non-empty canonical overlay shadow.
- Responsiveness and accessibility: at 390 × 844 CSS px the document has no
  page-level horizontal overflow (`scrollWidth = clientWidth = 390`), the card
  remains within 12–372 px, the form collapses to one column, and the navigation
  drawer remains operable. The tab strip retains its existing internal
  horizontal scrolling behavior.

## Comparison history

1. Baseline browser audit: the light-theme Settings card resolved to
   `rgb(30, 41, 59)` with a translucent white border; Files, Audit, and Custom
   Fields showed the same dark-only styling family. Severity: P1.
2. Fix: component-local undefined aliases and dark-only color literals were
   replaced with the existing semantic tokens from `apps/web/src/styles.css`.
3. Systemic sweep: the remaining undefined `--shadow-lg` and
   `--color-primary` references were replaced with `--shadow-overlay` and
   `--primary`; a source-wide token scan reports zero unresolved references.
4. Post-fix evidence: the affected routes, custom-field modal, and task
   overlays pass the computed browser contract; the same-viewport screenshot
   comparison shows the approved white content surface without structural
   redesign.

## Implementation checklist

- [x] Light surfaces use the canonical surface, border, and text tokens.
- [x] Dark surfaces use the canonical dark token values.
- [x] Files, Audit, Settings, and Custom Fields are covered.
- [x] The custom-field modal follows the active theme.
- [x] Desktop and mobile-sized browser states were inspected.
- [x] Theme toggle and mobile navigation interactions were exercised.
- [x] Searchable task overlays and fallback-only token paths were normalized.
- [x] No actionable P0/P1/P2 design mismatch remains in scope.

## Follow-up polish

No P3 follow-up is required for the approved theme-consistency repair. Broader
shell or copy changes would be a separate redesign and are intentionally out of
scope.

final result: passed
