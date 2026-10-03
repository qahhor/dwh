# SmartupCMS contributor onboarding

This path gives a new contributor a current, evidence-based view of the project.
SmartupCMS is a low-code CMS for developers; read the
[module development guide](guidelines/module-development-guide.md) and the
[extension points](architecture/extension-points.md) before writing a module.

## 1. Product and operating model

Read [README](../README.md), the
[ADR-0014](adr/ADR-0014-unified-open-source-runtime.md),
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
| `apps/server` | Spring Boot application (`com.smartup24.cms`), platform and business modules, APIs, migrations |
| `apps/web` | Angular application, UI kit and the entity components in `shared/entity` |
| `libs/core-types` | Shared domain primitives |
| `libs/platform-common` | Cross-module technical support |
| `libs/platform-api` | Public platform API a module builds against (ADR-0033) |
| `libs/provider-spi` | Storage and delivery provider contracts |
| `libs/platform-testkit`, `examples/external-module` | Test kit for modules outside the monorepo and an example module |
| `deploy/compose` | Production Compose and environment template |
| `deploy/images` | Hardened PostgreSQL, Typesense, proxy, and backup images |
| `scripts/prod` | Deploy, backup, restore, and release-contract checks |
| `e2e` | Playwright configuration and critical-flow tests |
| `docs/ops` | Active deployment and operations guidance |

The server packages follow the module prefixes: `common` (platform: entity
model, field registry, bulk, history), `md` (users, roles, rights, settings,
languages, menu), `kauth` (authentication), `ms` (tasks, projects, notes,
notifications), `mf` (files), `audit`, `search`, `webhook` (webhooks), `jobs`
(the shared job queue), `warehouse` (the second database and its load
ledger), `units` (units of measure), `upl` (data uploads), `report`
(exports), `analytics` (dashboard figures). `config` holds the infrastructure
(security, web filters, cache, retention job, idempotency, OpenAPI).

For a focused code question, build the local knowledge graph with
`graphify update .` (see `AGENTS.md`); it is not committed.

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

Treat superseded ADRs as historical evidence. Check current code and active operations docs before acting on them.

Server code follows the phase 3 rules of plan 10/10, and each has a test that
fails the build: errors are `ApiException` with a catalog key in ru/uz/en
([ADR-0021](adr/ADR-0021-error-model.md); `ErrorModelTest`, `ErrorTextsTest`),
the API description is generated from the code
([ADR-0022](adr/ADR-0022-openapi-from-code.md); `OpenApiContractTest`),
statuses and paths are uniform ([ADR-0023](adr/ADR-0023-uniform-rest.md);
`ResponseStatusDeclaredTest`), changes name their revision
([ADR-0024](adr/ADR-0024-optimistic-locking.md); `ChangesNameTheirRevisionTest`),
growing collections are paged (`CollectionsArePagedTest`), no catch swallows an
error (`NoSwallowedErrorsTest`), comments are in English
(`CommentLanguageTest`), and every business module keeps its coverage floor
(`scripts/quality/test-coverage-floors.ps1`). The short version is the
[server rules](guidelines/module-development-guide.md#серверные-правила) table;
the client view is [how the API behaves](api/README.md). The developer CLI
`cms` (`tools/cms-cli`, `node tools/cms-cli/bin/cms.mjs entity new ...`) produces code that
already passes them.

## 4. Verify the workspace

From the repository root:

```bash
mvn -B verify
```

```bash
cd apps/web
npm ci
npm run lint
npm run typecheck
npm test
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
