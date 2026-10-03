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

## 4. Run the product from the sources

Prerequisites: JDK 25 (on `PATH` or in `JAVA_HOME`), Node.js from
`.node-version` with npm, Docker with Compose v2. The Maven wrapper replaces a
Maven installation. The dev container (`.devcontainer/devcontainer.json`)
provides all three.

| Step | Linux, macOS, WSL, dev container | Windows (PowerShell) |
|---|---|---|
| Start everything, wait, print the address | `scripts/dev/run-local.sh` or `make dev` | `scripts\dev\run-local.ps1` |
| Same, prompt back at once | `scripts/dev/run-local.sh up --detach` (`make start`) | `scripts\dev\run-local.ps1 up -Detach` |
| With demo data | `--demo` (`make demo`) | `-Demo` |
| Only migrate both databases | `scripts/dev/run-local.sh migrate` (`make migrate`) | `scripts\dev\run-local.ps1 migrate` |
| Stop (data kept) | `scripts/dev/run-local.sh down` (`make stop`) | `scripts\dev\run-local.ps1 down` |
| Stop and delete the data | `down --volumes` (`make reset`) | `down -Volumes` |

On Windows run the script with `powershell -ExecutionPolicy Bypass -File …`
when the execution policy blocks local scripts.

What `up` does:

1. PostgreSQL 18 with both databases (the main one and pg-dwh) and the mail
   stub Mailpit start in Docker Compose (project `smartupcms-local`);
   `--search` also starts Typesense.
2. The server is built with `./mvnw` (tests skipped); `npm ci` runs meanwhile
   when `apps/web/node_modules` is missing.
3. Migrations: pg-dwh first, then the main database, as the migrator role,
   the same steps as the `migrate` service of `docker-compose.yml`.
4. The server starts with the `dev` profile (`dev,demo` with `--demo`;
   `mvn spring-boot:run -Pdevtools` with `--devtools`, which `make dev` uses),
   mail goes to Mailpit.
5. `ng serve` starts with the API proxied to the server.

Then open <http://localhost:4200> and sign in as `admin`; the password is
generated once into `.local/admin-password` and never printed; the first
sign-in asks for a new one. New users receive their invitation in Mailpit at
<http://localhost:8025>. Logs are in `.local/` (`server.log`, `web.log`,
`migrate.log`).

Ports come from the environment, so a second stand does not collide with the
first: `DB_PORT` (5432), `SERVER_PORT` (8080), `MANAGEMENT_PORT` (9090),
`WEB_PORT` (4200), `WEB_HOST` (localhost), `MAILPIT_HTTP_PORT` (8025),
`MAILPIT_SMTP_PORT` (1025), `TYPESENSE_PORT` (8108), and the Compose project
`SMC_LOCAL_PROJECT`.

The demo profile adds three fictional users (`demo.anna`, `demo.bobur`,
`demo.dilnoza`), two projects with six tasks, three notes and two orders,
switching the orders module on. Records go through the entity runtime as the
administrator and are looked up by their demo key first, so a restart adds
nothing. The profile is off by default and is never part of a deployment.

The nightly job `onboarding` runs `scripts/dev/test-onboarding-smoke.ps1`: it
clones the repository, runs these three commands (`git clone`, `cd`,
`run-local.sh up --detach --demo`) and fails when the UI, the API through the
UI origin, readiness, the administrator's sign-in or the demo data are not
there within 10 minutes. The same script runs on Windows on ports of its own
and tears its stand down.

## 5. Verify the workspace

From the repository root (or `make verify`):

```bash
./mvnw -B verify
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

## 6. First contribution checklist

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
