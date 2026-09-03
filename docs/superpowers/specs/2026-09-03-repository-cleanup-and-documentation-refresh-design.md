# SmartupCMS repository cleanup and documentation refresh

**Status:** Approved

**Date:** 2026-09-03

**Scope:** Repository hygiene and documentation accuracy. Product scope and
runtime behavior do not expand.

## 1. Objective

Make the repository describe one current product: the open-source,
single-organization SmartupCMS modular monolith deployed with Docker Compose.
Remove obsolete executable artifacts and generated noise left by the abandoned
Control Plane, fleet, Nomad, Consul, and Vault architecture. Establish one
current Russian-language technical specification without erasing useful ADR
history.

## 2. Source of truth

The implementation and supported runtime are defined by:

- `pom.xml`: one backend application module, `apps/server`;
- `apps/web`: one Angular browser application;
- `docker-compose.yml` and `deploy/compose/docker-compose.prod.yml`;
- `scripts/architecture/test-unified-boundaries.ps1`;
- the current security and operations documentation.

The supported model has no Control Plane, fleet registry, remote enrollment,
license gate, telemetry callback, Nomad, Consul, or mandatory Vault dependency.
One installation serves one organization and owns its PostgreSQL database,
object storage, search index, and operational lifecycle.

## 3. Cleanup boundary

### 3.1. Delete

The cleanup removes artifacts that contradict the current runtime or are fully
regenerable:

- root generated reports `REPORT.md` and `STATS_MAP.md`;
- `deploy/spike/`, including the obsolete Nomad/Vault proof of concept;
- obsolete Control Plane, Vault, and Nomad runbooks `RB-01` through `RB-03`;
- legacy `docs/audit/AUDIT-*` reports that assess a removed architecture;
- the 2026-09-01 Fleet Foundation design and implementation plan;
- generated Playwright CLI state;
- Graphify AST cache, query stamps, and dated backup snapshots.

Any other document is deleted only when its useful current content has first
been moved into a canonical active document.

### 3.2. Preserve

The cleanup must preserve:

- application source, database migrations, tests, and release automation;
- ignored `backups/` database archives and checksums;
- the running Docker stack and all Docker volumes;
- the ignored local `.env` file and secret material;
- current 2026-09-03 audit reports and evidence;
- ADRs as decision history, with explicit supersession notices;
- current Graphify outputs required for repository navigation;
- unrelated favicon, UI, E2E, audit-tracker, and Graphify changes already present
  in the working tree.

## 4. Documentation target state

### 4.1. Canonical technical specification

Create `docs/technical-specification.md` in Russian. It is the normative product
and engineering specification and contains:

- product purpose, actors, deployment model, and non-goals;
- current modules and critical user flows;
- functional requirements with stable identifiers;
- authentication, authorization, audit, file, search, notification, and
  administration requirements;
- data ownership, storage, migration, retention, and backup requirements;
- security, privacy, performance, capacity, observability, and availability
  requirements;
- release, upgrade, rollback, and acceptance criteria;
- explicitly documented limitations and unresolved operator decisions.

Public README and operational documentation remain in English. The Russian
technical specification is the single normative requirements document; it is
not duplicated in a second language.

### 4.2. Navigation and active documents

Create `docs/README.md` as the documentation index. Rewrite the monorepo map and
update active architecture, module, migration, testing, operations, security,
and runbook documents where they conflict with the current implementation.
Broken references to removed TRD/TZ documents are replaced with links to the
canonical technical specification or current ADRs.

### 4.3. ADR history

Historical ADR bodies remain available. Stale accepted statuses receive an
explicit supersession or partial-supersession notice pointing to a new ADR for
the unified open-source runtime. Historical text is not silently rewritten to
look current.

## 5. Generated artifacts policy

Keep Graphify's current navigation artifacts that are referenced by
`AGENTS.md`. Ignore and remove its cache, query stamps, and dated backup
directories. Ignore local Playwright CLI state. Regenerate the current graph
after implementation with `graphify update .`.

## 6. Enforcement

Extend repository documentation and architecture checks so active runtime and
active documentation cannot reintroduce:

- removed backend or frontend application directories;
- Control Plane or fleet Compose services;
- mandatory phone-home, licensing, enrollment, Nomad, Consul, or Vault runtime
  dependencies;
- links to non-existent TRD/TZ documents;
- tracked generated Graphify caches or Playwright CLI state.

Historical ADR text and migration history are explicitly allow-listed.

## 7. Verification

The change is complete when:

1. Git contains no approved obsolete artifacts or tracked generated caches.
2. `git status` proves backups, Docker data, and unrelated pre-existing changes
   were not deleted or overwritten.
3. Every active documentation link resolves to an existing repository file.
4. Active documentation consistently describes one SmartupCMS server, one web
   application, PostgreSQL, Typesense, optional ClamAV, and optional backup.
5. Architecture and public-document contract scripts pass.
6. Graphify is updated and its cache remains ignored.
7. The running Docker stack and persistent volumes remain intact.

## 8. Non-goals

- No product feature, API, schema, or UI redesign.
- No deletion of database backups or Docker volumes.
- No production deployment or provider migration.
- No rewriting of Git history.
- No removal of database migrations that record the former architecture.
