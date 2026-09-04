# SmartupCMS design system

## Product and users

SmartupCMS is a self-hosted enterprise content and operations platform. The UI
must favor clarity, density, predictability and keyboard accessibility for
administrators and operational users. Preserve the current information
architecture and all existing workflows.

## Visual direction

- Keep the existing restrained enterprise aesthetic.
- Use Inter and Material Symbols Outlined only.
- Keep the navigation sidebar intentionally dark in both themes.
- In light theme, all content cards, tables, panels, modals and controls use
  light semantic surfaces. No dark content islands are allowed.
- In dark theme, the same components switch through semantic tokens; do not
  introduce component-specific theme palettes.
- Avoid gradients, glassmorphism, decorative illustrations, oversized type and
  marketing-page spacing.

## Canonical semantic tokens

| Purpose | Token |
|---|---|
| Application canvas | `--bg-app` |
| Card, table, modal and input surface | `--bg-surface` |
| Hover/subtle header surface | `--bg-hover` |
| Selected/pressed surface | `--bg-active` |
| Standard border | `--border-color` |
| Subtle row separator | `--border-subtle` |
| Primary text | `--text-main` |
| Secondary text | `--text-muted` |
| Tertiary text | `--text-light` |
| Brand/action | `--primary`, `--primary-hover`, `--primary-subtle` |
| Focus | `--focus-ring` |
| Elevation | `--shadow-sm`, `--shadow-md`, `--shadow-overlay` |

Do not use the obsolete names `--bg-card`, `--text-primary`,
`--text-secondary`, or `--color-primary`. Do not attach dark hex fallbacks to
semantic tokens.

## Light theme

- Canvas `#f8fafc`; content surfaces `#ffffff`.
- Main text `#0f172a`; secondary text `#334155`; tertiary text `#475569`.
- Borders `#e2e8f0`; subtle fills `#f1f5f9`.
- Primary action `#0369a1`; hover `#075985`; subtle accent `#e0f2fe`.
- Inputs and selects must be white with a visible neutral border and dark text.

## Dark theme

- Canvas `#090d16`; content surfaces `#131b2e`.
- Main text `#f1f5f9`; secondary text `#94a3b8`.
- Borders `#1e293b`; hover surfaces `#1c263d`.
- Primary action `#38bdf8`; hover `#0ea5e9`; subtle accent `#082f49`.

## Components

- Cards and tables: 8px radius, one-pixel semantic border, subtle elevation.
- Form controls: 34px minimum height, 4px radius, semantic surface/border/text,
  visible focus ring, associated labels and inline error text.
- Tabs: neutral container, white/dark semantic selected surface, compact 28px
  height.
- Buttons: primary, secondary, danger and ghost variants through shared
  `ui-button`; one clear primary action per panel.
- Modals: semantic surface, shared `ui-modal`, dimmed navy overlay only.
- Tables: compact header, 11px uppercase labels, responsive horizontal scroll.

## Layout and responsiveness

- Preserve the 220px desktop sidebar, 60px compact sidebar and 52px top bar.
- Preserve 20px desktop content padding and 12px mobile padding.
- Forms use a two-column grid where space allows and one column below 720px.
- No horizontal clipping at 390px mobile viewport; tables may scroll inside
  their own container.

## Accessibility and states

- WCAG 2.1 AA contrast for text and interactive controls.
- Visible `:focus-visible` state for every control.
- Keep loading, error, empty, permission and success states readable in both
  themes.
- Disabled controls remain identifiable and do not rely on color alone.

## Repair target

The approved direction is a systemic theme repair, not a new visual identity.
Correct Files, Audit, Settings and Custom Fields, plus the shared task overlay
tokens, while preserving the existing Tasks information architecture and the
already consistent Projects, Analytics, Users, Roles, Profile, Notifications,
Announcements and System pages.
