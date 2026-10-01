# SmartupCMS documentation

## Start here: building on the platform

SmartupCMS is a low-code CMS for developers: an entity is declared once on the
server and the platform builds its list, form, card, history, export, bulk
actions, menu item and permission names.

1. [README](../README.md) — what the platform is and what a module consists of.
2. [Module development guide](guidelines/module-development-guide.md) — the
   checklist for a declared entity and a screen example.
3. [Extension points](architecture/extension-points.md) — every interface and
   component a module plugs into, and the known gaps.
4. [ADR-0019 — low-code entity model](adr/ADR-0019-low-code-entity-model.md)
   and [ADR-0016 — field registry and filter DSL](adr/ADR-0016-field-registry-query-dsl.md).
5. [Developer onboarding](onboarding.md) — the code map and the verification
   commands.

This index defines the authority and navigation order for active project
documentation. Public operations documentation remains in English. The
canonical requirements document is the Russian technical specification.

## Authority tier 1 — requirements

- [Canonical technical specification](technical-specification.md) — the single
  normative requirements baseline, with stable `FR-*`, `NFR-*`, and `AC-*`
  identifiers.

If another document disagrees with the specification, current behavior is
checked against code and automated contracts and the discrepancy is tracked as
a documentation defect until requirements are formally changed.

## Authority tier 2 — current decisions

[ADR-0014](adr/ADR-0014-unified-open-source-runtime.md) defines the current
unified open-source runtime and overrides the superseded portions of older
decisions.

Current ADRs that are not superseded:

- [ADR-0002 — backend stack](adr/ADR-0002-backend-stack.md)
- [ADR-0005 — AI/ML readiness](adr/ADR-0005-ai-ml-readiness.md)
- [ADR-0012 — UI foundation](adr/ADR-0012-ui-foundation.md)
- [ADR-0013 — data scope](adr/ADR-0013-data-scope.md)
- [ADR-0015 — UI kit transfer policy](adr/ADR-0015-ui-kit-transfer-policy.md)
  — vendors a subset of the shared component library instead of depending on
  it, and amends ADR-0012 for that subset only.
- [ADR-0016 — field registry, query-meta and filter DSL](adr/ADR-0016-field-registry-query-dsl.md)
  — lists declare their fields on the server; clients read them from
  `query-meta` and filter and sort through one checked JSON DSL.
- [ADR-0017 — record history tab](adr/ADR-0017-record-history.md)
- [ADR-0018 — asynchronous list exports and their journal](adr/ADR-0018-async-exports.md)
  — a list exports to xlsx through a `QueryListExporter` bean over the same
  pages, scope and field rights as the screen; a queued job writes the file,
  and the export journal (`report_exports`, 7 days) serves it to its owner.
- [ADR-0019 — low-code entity model](adr/ADR-0019-low-code-entity-model.md)
  — an entity is one server declaration from which lists, forms, cards,
  permissions and menus are built.
- [ADR-0020 — database naming and types](adr/ADR-0020-database-naming.md)
  — migrations from V128 use identity keys, `text` with checks, `timestamptz`,
  `modified_at` and `<table>_<columns>_idx|_uq` names; `MigrationLintTest`
  enforces them.
- [ADR-0021 — one error model](adr/ADR-0021-error-model.md)
  — every request error is an `ApiException` with an `ErrorCode`, a catalog key
  and parameters, answered as `application/problem+json` in the request
  language.
- [ADR-0022 — the API description comes from the code](adr/ADR-0022-openapi-from-code.md)
  — `docs/api/openapi.json` is generated from the controllers; the web types
  and the breaking-change check read it.
- [ADR-0023 — uniform REST](adr/ADR-0023-uniform-rest.md)
  — one path per operation, camelCase parameters, statuses that say what
  happened, switches that take their state; old forms answer for one release.
- [ADR-0024 — mandatory optimistic locking](adr/ADR-0024-optimistic-locking.md)
  — a change names the revision it was made from (If-Match); none is 428, a
  stale one 409; switches write only when the state changes.
- [ADR-0025 — retention of journals and the cache across nodes](adr/ADR-0025-retention-and-cluster-cache.md)
  — modules declare how long their journals live and a nightly job trims them;
  a cache cleared on one node is cleared on all after the commit.
- [ADR-0026 — published read views](adr/ADR-0026-published-read-views.md)
  — a module publishes read-only `<prefix>_pub_*` views of the columns other
  modules read; their repositories join the views, never the base tables, and
  change another module's data only through its service.
- [ADR-0027 — configuration names: smc and warehouse](adr/ADR-0027-configuration-names.md)
  — product settings are `smc.*` / `SMC_*`, warehouse settings `warehouse.*` /
  `WAREHOUSE_*`; old `dwh` names are read with a warning until 2026-12-31;
  published development secrets stop a start outside dev and test.
- [ADR-0028 — permission codes by module](adr/ADR-0028-permission-codes.md)
  — a form code is `<area>.<entity-or-screen>` and its area names the owning
  module; a controller guards its endpoints with its own module's forms or
  with forms the owner publishes to it; V147 moved the old `iam.*`, `rbac.*`
  and `platform.*` grants.
- [ADR-0029 — encryption of secrets in the database](adr/ADR-0029-stored-secrets-encryption.md)
  — webhook signing keys and SSO client secrets are stored as AES-256-GCM
  `v1:` values under the installation key `SMC_SECRETS_KEY`; the server
  refuses to start without the key outside dev and encrypts legacy plain
  values at start.
- [ADR-0030 — splitting fnd into jobs, warehouse and units](adr/ADR-0030-fnd-split.md)
  — the shared job queue is the `jobs` module, the second database with its
  load ledger is `warehouse`, units of measure are `units`, versioning and the
  audit actor are platform contracts in `common`; SQL lives only in
  repositories (no `*Service` runs SQL), the queue never depends on the
  warehouse, and message keys stay `error.fnd.*`.

The following ADRs remain current only outside the areas explicitly replaced
by ADR-0014:

- [ADR-0001 — architecture model](adr/ADR-0001-architecture-model.md)
- [ADR-0003 — tenancy and RBAC](adr/ADR-0003-tenancy-rbac.md)
- [ADR-0006 — modular monolith](adr/ADR-0006-modular-monolith.md)
- [ADR-0008 — security baseline](adr/ADR-0008-security-baseline.md)
- [ADR-0009 — observability](adr/ADR-0009-observability.md)
- [ADR-0010 — resilience tiers](adr/ADR-0010-resilience-tiers.md)
- [ADR-0011 — provider SPI](adr/ADR-0011-provider-spi.md)

Historical, fully superseded decisions are retained for traceability only:
[ADR-0004](adr/ADR-0004-deployment-model.md) and
[ADR-0007](adr/ADR-0007-fleet-strategy.md).

## Authority tier 3 — engineering guidance

- [Developer onboarding](onboarding.md)
- [Code style](../CODE_STYLE.md) — formatting, comments, errors, API, paging,
  locking and size limits, each rule with the check that enforces it.
- [How the API behaves](api/README.md) — errors, paging, If-Match, idempotency,
  deprecations and where `openapi.json` comes from.
- [Extension points](architecture/extension-points.md)
- [Biruni and Smartup architecture conventions](architecture/biruni-smartup-conventions.md)
- [Monorepo structure](architecture/monorepo-structure.md)
- [Database migration guidelines](guidelines/database-migrations.md)
- [Module development guide](guidelines/module-development-guide.md)
- [Testing strategy](guidelines/testing-strategy.md)

Engineering guidance explains how to implement the current requirements and
decisions. It does not redefine either of them.

For UI copy, add a stable domain key to
`apps/server/src/main/resources/i18n/ru.json`, reference it through `t` or
`I18nService.translate`, run `npm run i18n:sync-ru`, then run
`npm run i18n:audit`. Non-Russian catalogs may be incomplete; missing values
must resolve per key through the effective Russian dictionary and are exposed
as coverage in Settings. Error keys (`error.<module>.<name>`) are the
exception: `ErrorTextsTest` requires each of them in `ru`, `uz` and `en`.

## Authority tier 4 — operations and security

- [Operations architecture](ops/architecture-overview.md)
- [Production deployment](ops/deployment-guide.md)
- [Server configuration reference](ops/configuration-reference.md)
- [Maintenance, backup, and restore](ops/maintenance-guide.md)
- [Search and index maintenance](ops/search-and-index-maintenance.md)
- [Operations runbook](ops/operations-runbook.md)
- [Privacy, data protection, and retention annex](ops/privacy-and-retention-annex.md)
- [Production launch checklist](ops/production-launch-checklist.md)
- [Smartup-managed infrastructure acceptance](ops/managed-infrastructure-acceptance.md)
- [Rollback and recovery](ops/rollback.md)
- [Migration history repair](ops/migration-repair.md)
- [GitHub repository settings](ops/repository-settings.md)
- [RB-04 migration failure triage](runbooks/RB-04-migration-failure-triage.md)
- [Threat model and personal-data inventory](security/threat-model.md)

These documents govern execution for a concrete installation but cannot supply
missing product requirements, an unapproved SLO, or installation-specific
legal and ownership decisions.

## Project entry points and historical material

- [Project overview and quick start](../README.md)
- [AI project context](ai-context.md) — concise handoff for AI-assisted work;
  subordinate to this authority model and the canonical specification.
- [План 10/10](plan-10-10.md) — фазы качества, критерии приёмки и статус; цель ссылок «plan 10/10, item N».
- [Contribution guide](../CONTRIBUTING.md)

Dated audit reports and agent plans were removed from the tree; git history
keeps them. The canonical ТЗ and current ADRs are the only authority.
