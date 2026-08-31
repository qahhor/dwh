# A-01 — Restore documentation as release source of truth

**Priority:** P1 · **Effort:** M · **Owner:** TBD

## Problem and evidence

README says Angular 20 while package manifests use 22.1.4; links target deleted TRD/plan files; monorepo document lists absent modules/deploy layouts. Old readiness audit claims production-ready while runbooks admit no WAL/encryption/autocheck. Manual OpenAPI exposes only a fraction of controllers.

## Minimal change

- Mark obsolete audit/status docs `SUPERSEDED` and link this release audit.
- Generate/validate repo inventory, internal links and documented commands in CI.
- One release runbook: exact artifact, deploy, migrations, smoke, rollback, restore, known limitations and owners.
- Mark OpenAPI partial immediately; then generate from controller contracts or cover release-critical API with contract tests.

## Verification

Clean developer onboarding and operator rehearsal follow docs without tribal knowledge; internal link checker reports zero errors; documented versions/commands match manifests and CI; API diff is reviewed.
