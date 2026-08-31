# W-01 — Restore UI accessibility and state consistency

**Priority:** P1 · **Effort:** M · **Owner:** TBD

## Problem and evidence

Current web-instance unit run has role/aria-label/i18n regressions. Instance modal traps focus; CP modal does not. `CONTRIBUTING.md:38` promises zero critical axe issues, but CI has no axe gate.

## Minimal change

- Fix current failing contracts; add CP modal focus trap/autocapture/restore.
- Standardize loading/empty/error/forbidden state primitives only across release-critical screens.
- Axe smoke for login, shell, task dialog, IAM dialog and CP fleet/module dialog; keyboard test for modal/menu.
- Consolidate the two CP pagination implementations.

## Verification

Both unit suites green; 0 critical axe findings on selected flows; Tab/Shift+Tab/Escape/focus restore tests pass at 1280×720 and 390×844 with no horizontal overflow.
