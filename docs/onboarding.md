# SmartupCMS contributor onboarding

This path gives a new contributor a current, evidence-based view of the project
without requiring historical audit documents.

## 1. Product and operating model

Read [README](../README.md), the
[unified open-source design](superpowers/specs/2026-09-02-smartupcms-unified-open-source-design.md),
and [ADR-0006](adr/ADR-0006-modular-monolith.md). The essential constraints are:

- one installation belongs to one organization and serves many users;
- the complete product is self-hostable;
- runtime behavior is local unless an administrator explicitly configures a
  provider;
- database migrations and backups are operator-controlled, fail-closed steps.

Historical ADRs remain in the repository as decision records. An ADR marked
`Заменено` is not an active operating instruction.

## 2. Repository map

| Path | Purpose |
|---|---|
| `apps/server` | Spring Boot application, business modules, APIs, migrations |
| `apps/web` | Angular application and design system |
| `libs/core-types` | Shared domain primitives |
| `libs/platform-common` | Cross-module technical support |
| `libs/provider-spi` | Storage and delivery provider contracts |
| `deploy/compose` | Production Compose and environment template |
| `deploy/images` | Hardened PostgreSQL, Typesense, proxy, and backup images |
| `scripts/prod` | Deploy, backup, restore, and release-contract checks |
| `e2e` | Playwright configuration and critical-flow tests |
| `docs/ops` | Active deployment and operations guidance |

For a focused code question, use the repository knowledge graph described in
`AGENTS.md`; generated `graphify-out` files are not product source.

## 3. Architecture and security

Read these documents before changing their area:

- [ADR-0001](adr/ADR-0001-architecture-model.md): application and database
  responsibility.
- [ADR-0003](adr/ADR-0003-tenancy-rbac.md): historical tenancy decision; the
  active model is separate installation and database per organization.
- [ADR-0008](adr/ADR-0008-security-baseline.md): security baseline.
- [ADR-0009](adr/ADR-0009-observability.md): health and observability model.
- [ADR-0011](adr/ADR-0011-provider-spi.md): provider boundaries.
- [ADR-0012](adr/ADR-0012-ui-foundation.md): UI foundation.
- [Database migrations](guidelines/database-migrations.md) and
  [testing strategy](guidelines/testing-strategy.md).

Treat `docs/audit`, `docs/superpowers/plans`, and superseded ADRs as historical
evidence. Check current code and active operations docs before acting on them.

## 4. Verify the workspace

From the repository root:

```bash
mvn -B verify
```

```bash
cd apps/web
npm ci
npm test
npm run typecheck
npm run build
```

For the running product, follow the [quick start](../README.md#quick-start).
Before changing deployment behavior, read the
[deployment guide](ops/deployment-guide.md) and
[rollback procedure](ops/rollback.md).

## 5. First contribution checklist

- Read [CONTRIBUTING](../CONTRIBUTING.md) and sign every commit with `-s`.
- Confirm the issue states the user problem, acceptance criterion, and non-goals.
- Locate the owning module and its authorization boundary.
- Write or update the smallest test that proves the behavior.
- Run the relevant backend, web, release, and E2E gates.
- Update documentation and the changelog when behavior changes.
- Verify that no secret, personal data, dump, or generated graph artifact is
  staged.

If the expected behavior, data owner, permission, migration path, or rollback is
not documented, raise that uncertainty in the issue or pull request rather than
guessing.
