# SmartupCMS Repository Cleanup and Documentation Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove obsolete repository artifacts without deleting customer or runtime data, then establish one accurate documentation set and one canonical Russian technical specification for the unified open-source SmartupCMS product.

**Architecture:** Treat the current Maven reactor, Angular application, Compose topologies, and unified-boundary script as implementation evidence. Delete only approved generated or superseded artifacts, preserve ADR decision history with explicit status changes, and enforce the target state through PowerShell contracts in CI. Keep current Graphify navigation outputs while making its caches and dated backups local-only.

**Tech Stack:** PowerShell 7, Git, Markdown, GitHub Actions, Graphify, Docker Compose, Java 25/Spring Boot 4.1, Angular 22, PostgreSQL 18, Typesense 27.1.

**Spec:** `docs/superpowers/specs/2026-09-03-repository-cleanup-and-documentation-refresh-design.md`

## Global Constraints

- Product scope and runtime behavior do not expand.
- Preserve `backups/`, `.env`, the running Docker stack, and all Docker volumes.
- Preserve current 2026-09-03 audit reports and unrelated dirty UI, E2E, audit-tracker, and Graphify work.
- Preserve database migration history and historical ADR bodies.
- One installation serves one organization and uses `apps/server`, `apps/web`, PostgreSQL, Typesense, optional ClamAV, and optional encrypted backup.
- No Control Plane, fleet registry, remote enrollment, license gate, telemetry callback, Nomad, Consul, or mandatory Vault dependency is part of the supported runtime.
- Smartup-managed storage targets Cloudflare R2; self-hosters may use any compatible S3 provider.
- Public README and operations documents remain English; `docs/technical-specification.md` is the single normative Russian requirements document.
- Use `apply_patch` for authored text changes. Before bulk generated-file removal, resolve and print every absolute target and verify it remains inside `D:\Claude\dwh\graphify-out` or `D:\Claude\dwh\.playwright-cli`.
- Every implementation commit is DCO-signed with `git commit -s`.

---

## File Map

- `.gitignore`: local/generated artifact policy.
- `scripts/docs/test-public-docs.ps1`: required active documents, ADR statuses, retired identifiers, and relative-link validation.
- `scripts/docs/test-repository-hygiene.ps1`: approved obsolete-path and tracked-generated-artifact contract.
- `.github/workflows/ci.yml`: executes both documentation contracts.
- `docs/technical-specification.md`: canonical Russian functional and non-functional requirements.
- `docs/README.md`: navigation map and document authority rules.
- `docs/adr/ADR-0014-unified-open-source-runtime.md`: current architecture decision.
- `docs/adr/ADR-0001`, `0003`, `0004`, `0006` through `0011`: historical status banners only; bodies remain history.
- `docs/architecture/monorepo-structure.md`: actual repository/runtime map.
- `docs/architecture/biruni-smartup-conventions.md`: current naming and module conventions.
- `docs/guidelines/database-migrations.md`: Compose migration and rollback contract.
- `docs/guidelines/module-development-guide.md`: current modular-monolith development rules.
- `docs/guidelines/testing-strategy.md`: current local and CI quality gates.
- `docs/runbooks/RB-04-migration-failure-triage.md`: current Compose migration incident procedure.
- `README.md`: links to documentation index and canonical technical specification.
- `audit/health-check-2026-09-03.md`: evidence-backed post-cleanup repository health report.
- `graphify-out/graph.json`, `graphify-out/graph.html`, `graphify-out/GRAPH_REPORT.md`, labels, and manifest: regenerated current navigation artifacts.

---

### Task 1: Add fail-first repository hygiene and documentation contracts

**Files:**

- Create: `scripts/docs/test-repository-hygiene.ps1`
- Modify: `.gitignore`
- Modify: `scripts/docs/test-public-docs.ps1:4-95`
- Modify: `.github/workflows/ci.yml:68-75`

**Interfaces:**

- Consumes: Git tracked-file list and the repository root resolved from `$PSScriptRoot`.
- Produces: two exit-code contracts: public documentation correctness and repository hygiene.

- [ ] **Step 1: Extend the generated-artifact ignore policy**

Append these exact patterns to `.gitignore`:

```gitignore

# --- Local browser automation state ---
.playwright-cli/

# --- Graphify generated caches and snapshots ---
graphify-out/cache/
graphify-out/[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/
graphify-out/last_query_stamp
```

- [ ] **Step 2: Create a hygiene contract that initially fails**

Create `scripts/docs/test-repository-hygiene.ps1` with this complete behavior:

```powershell
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$errors = [System.Collections.Generic.List[string]]::new()

$obsoletePaths = @(
    'REPORT.md',
    'STATS_MAP.md',
    'deploy/spike',
    'docs/audit',
    'docs/runbooks/RB-01-node-failure-recovery.md',
    'docs/runbooks/RB-02-vault-unseal-and-raft-quorum.md',
    'docs/runbooks/RB-03-license-key-emergency-rotation.md',
    'docs/superpowers/specs/2026-08-30-e2e-browser-testing-design.md',
    'docs/superpowers/specs/2026-08-30-ui-ux-hardening-design.md',
    'docs/superpowers/specs/2026-09-01-fleet-foundation-design.md',
    'docs/superpowers/plans/2026-08-30-e2e-browser-testing-implementation.md',
    'docs/superpowers/plans/2026-08-30-ui-feature-screens.md',
    'docs/superpowers/plans/2026-08-30-ui-foundation-entry-shell.md',
    'docs/superpowers/plans/2026-09-01-fleet-foundation-control-plane-contract-security-implementation.md'
)

$obsoleteAuditPaths = @(
    'audit/00-master-improvement-plan-2026-08-31.md',
    'audit/00-master-improvement-plan-2026-09-02.md',
    'audit/architecture-2026-08-31.md',
    'audit/architecture-2026-09-02.md',
    'audit/code-quality-2026-08-31.md',
    'audit/devops-2026-08-31.md',
    'audit/devops-2026-09-02.md',
    'audit/documentation-2026-08-31.md',
    'audit/documentation-2026-09-02.md',
    'audit/health-check-2026-08-31.md',
    'audit/performance-2026-08-31.md',
    'audit/security-2026-08-31.md',
    'audit/security-2026-09-02.md',
    'audit/testing-2026-08-31.md',
    'audit/testing-2026-09-02.md',
    'audit/widgets-2026-08-30.md',
    'audit/widgets-2026-08-31.md',
    'audit/widgets-2026-09-02.md',
    'audit/evidence/fleet-foundation-cp-contract-2026-09-01.md',
    'audit/evidence/verification-2026-08-31.md',
    'audit/evidence/web-cp-login-desktop.png',
    'audit/evidence/web-cp-login-mobile.png',
    'audit/evidence/web-instance-login-desktop.png',
    'audit/evidence/web-instance-login-mobile.png'
)

$obsoletePaths += $obsoleteAuditPaths

foreach ($relativePath in $obsoletePaths) {
    if (Test-Path -LiteralPath (Join-Path $repoRoot $relativePath)) {
        $errors.Add("Obsolete repository artifact remains: $relativePath")
    }
}

Push-Location $repoRoot
try {
    $tracked = @(git ls-files)
    if ($LASTEXITCODE -ne 0) {
        throw 'git ls-files failed'
    }
}
finally {
    Pop-Location
}

foreach ($relativePath in $tracked) {
    if ($relativePath -match '^graphify-out/cache/' -or
        $relativePath -match '^graphify-out/\d{4}-\d{2}-\d{2}/' -or
        $relativePath -match '^\.playwright-cli/') {
        $errors.Add("Generated artifact is tracked: $relativePath")
    }
}

if ($errors.Count -gt 0) {
    throw "Repository hygiene contract failed:`n - $($errors -join "`n - ")"
}

Write-Host 'Repository hygiene contract passed.'
```

- [ ] **Step 3: Run the hygiene contract and verify the expected red state**

Run:

```powershell
./scripts/docs/test-repository-hygiene.ps1
```

Expected: FAIL listing `REPORT.md`, `STATS_MAP.md`, `deploy/spike`, legacy docs, and tracked `graphify-out/cache/*` or dated Graphify snapshots.

- [ ] **Step 4: Expand the public-document contract**

In `scripts/docs/test-public-docs.ps1`:

- add `docs/README.md`, `docs/technical-specification.md`, and `docs/adr/ADR-0014-unified-open-source-runtime.md` to required files;
- add `docs/README.md`, `docs/technical-specification.md`, both architecture documents, all three guideline documents, and RB-04 to active docs;
- change the retired identifier expression to:

```powershell
$retiredTerms = '(?i)apps/(?:instance|control-plane|web-instance|web-cp)|docker-compose\.fleet|migrate-cp|DWH_CP_|TRD-\d|TZ-\d'
```

- require ADR-0004 and ADR-0007 to contain `Заменено ADR-0014`;
- require ADR-0001, ADR-0003, ADR-0006, ADR-0008, ADR-0009, ADR-0010, and ADR-0011 to contain `Частично заменено ADR-0014` or `Частично заменён ADR-0014`;
- validate relative Markdown links in every required/active/status-checked Markdown file.

- [ ] **Step 5: Wire the hygiene contract into CI**

After the public documentation contract step in `.github/workflows/ci.yml`, add:

```yaml
      - name: Validate repository hygiene contract
        shell: pwsh
        run: ./scripts/docs/test-repository-hygiene.ps1
```

- [ ] **Step 6: Verify both contracts are red for only the intended missing/obsolete state**

Run:

```powershell
./scripts/docs/test-public-docs.ps1
./scripts/docs/test-repository-hygiene.ps1
```

Expected: public-docs FAIL for the three not-yet-created canonical documents and ADR status markers; hygiene FAIL for approved obsolete artifacts and tracked Graphify generated data. No failure may mention `backups`, `.env`, Docker volumes, favicon files, or current 2026-09-03 audit reports.

- [ ] **Step 7: Commit the guardrails**

```powershell
git add .gitignore scripts/docs/test-public-docs.ps1 scripts/docs/test-repository-hygiene.ps1 .github/workflows/ci.yml
git commit -s -m "test(docs): enforce repository hygiene"
```

---

### Task 2: Remove approved obsolete and generated artifacts

**Files:**

- Delete: `REPORT.md`
- Delete: `STATS_MAP.md`
- Delete: `deploy/spike/`
- Delete: `docs/audit/`
- Delete: `docs/runbooks/RB-01-node-failure-recovery.md`
- Delete: `docs/runbooks/RB-02-vault-unseal-and-raft-quorum.md`
- Delete: `docs/runbooks/RB-03-license-key-emergency-rotation.md`
- Delete: `docs/superpowers/specs/2026-08-30-e2e-browser-testing-design.md`
- Delete: `docs/superpowers/specs/2026-08-30-ui-ux-hardening-design.md`
- Delete: `docs/superpowers/specs/2026-09-01-fleet-foundation-design.md`
- Delete: `docs/superpowers/plans/2026-08-30-e2e-browser-testing-implementation.md`
- Delete: `docs/superpowers/plans/2026-08-30-ui-feature-screens.md`
- Delete: `docs/superpowers/plans/2026-08-30-ui-foundation-entry-shell.md`
- Delete: `docs/superpowers/plans/2026-09-01-fleet-foundation-control-plane-contract-security-implementation.md`
- Delete: dated audit reports from 2026-08-30 through 2026-09-02 listed in Task 1's `$obsoleteAuditPaths`, plus their obsolete Control Plane screenshots and pre-unification evidence
- Preserve: `audit/*2026-09-03.md`, `audit/evidence/cto-audit-2026-09-03.md`, `audit/evidence/smartupcms-unified-release-2026-09-02.md`, and `audit/fixes/`
- Delete from Git and local workspace: `graphify-out/cache/`, `graphify-out/2026-08-29/`, `graphify-out/2026-08-30/`, `graphify-out/2026-08-31/`, `graphify-out/2026-09-01/`, `graphify-out/2026-09-03/`, and `.playwright-cli/`

**Interfaces:**

- Consumes: the approved deletion boundary from the design specification.
- Produces: a clean active tree while retaining current Graphify outputs and all business/runtime data.

- [ ] **Step 1: Prove protected data exists and record container state**

Run:

```powershell
Get-ChildItem -LiteralPath backups -Recurse -File | Select-Object FullName,Length
docker compose ps --format json
docker volume ls --filter name=smartupcms
```

Expected: backup files are listed, the current Compose stack is visible, and existing `smartupcms_*` volumes are recorded. Save the command output only in the terminal; do not copy backup names or secrets into public documentation.

- [ ] **Step 2: Resolve every bulk-generated target before deletion**

Run:

```powershell
$repoRoot = (Resolve-Path '.').Path
$targets = @(
    'graphify-out/cache',
    'graphify-out/2026-08-29',
    'graphify-out/2026-08-30',
    'graphify-out/2026-08-31',
    'graphify-out/2026-09-01',
    'graphify-out/2026-09-03',
    '.playwright-cli'
)
$resolvedTargets = foreach ($target in $targets) {
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $target))
    if (-not $candidate.StartsWith($repoRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing target outside repository: $candidate"
    }
    $candidate
}
$resolvedTargets
```

Expected: every path starts with `D:\Claude\dwh\`; none resolves to the repository root, `backups`, or a Docker data location.

- [ ] **Step 3: Delete the approved textual and PoC files**

Use `apply_patch` delete operations for the root reports, runbooks, listed superseded Superpowers documents, listed dated audit artifacts, and files under `docs/audit/` and `deploy/spike/`. Do not delete the 2026-09-02 unified-open-source design/plan, the 2026-09-03 cleanup design/plan, any current 2026-09-03 audit, `audit/evidence/smartupcms-unified-release-2026-09-02.md`, `audit/fixes/`, or any ADR.

- [ ] **Step 4: Remove only the validated generated directories**

Use native PowerShell `Remove-Item -LiteralPath <validated-absolute-path> -Recurse -Force` once for each path produced in Step 2. Do not pass globs, environment variables, `~`, a workspace root, `backups`, or any Docker volume path.

- [ ] **Step 5: Verify the hygiene contract turns green**

Run:

```powershell
./scripts/docs/test-repository-hygiene.ps1
git status --short
```

Expected: PASS. Git status shows the approved deletions and existing unrelated changes. No path under `backups/` appears.

- [ ] **Step 6: Verify Docker data was preserved**

Run:

```powershell
docker compose ps --format json
docker volume ls --filter name=smartupcms
```

Expected: the same named volumes still exist; running services remain present and healthy.

- [ ] **Step 7: Commit only cleanup and generated-artifact policy results**

```powershell
git add -u REPORT.md STATS_MAP.md deploy/spike docs/audit docs/runbooks docs/superpowers audit graphify-out/cache graphify-out/2026-08-29 graphify-out/2026-08-30 graphify-out/2026-08-31 graphify-out/2026-09-01
git commit -s -m "chore(repo): remove obsolete platform artifacts"
```

Do not stage current `audit/*2026-09-03.md`, favicon/UI/E2E changes, or current modified Graphify outputs in this commit.

---

### Task 3: Record the unified runtime ADR and correct historical statuses

**Files:**

- Create: `docs/adr/ADR-0014-unified-open-source-runtime.md`
- Modify: `docs/adr/ADR-0001-architecture-model.md`
- Modify: `docs/adr/ADR-0003-tenancy-rbac.md`
- Modify: `docs/adr/ADR-0004-deployment-model.md`
- Modify: `docs/adr/ADR-0006-modular-monolith.md`
- Modify: `docs/adr/ADR-0007-fleet-strategy.md`
- Modify: `docs/adr/ADR-0008-security-baseline.md`
- Modify: `docs/adr/ADR-0009-observability.md`
- Modify: `docs/adr/ADR-0010-resilience-tiers.md`
- Modify: `docs/adr/ADR-0011-provider-spi.md`

**Interfaces:**

- Consumes: actual Maven/Compose boundaries and approved open-source product model.
- Produces: ADR-0014 as the current decision and machine-checkable supersession markers in historical ADRs.

- [ ] **Step 1: Create ADR-0014**

Write the ADR in Russian with these exact decisions:

```markdown
# ADR-0014: Единый open-source runtime SmartupCMS

**Статус:** Принято (2026-09-03)

**Заменяет:** ADR-0004 и ADR-0007 полностью; ADR-0001, ADR-0003,
ADR-0006, ADR-0008, ADR-0009, ADR-0010 и ADR-0011 — только в частях,
зависящих от Control Plane, fleet, лицензирования, Nomad, Consul или
обязательного Vault.
```

The body must state:

- one installation equals one organization and one PostgreSQL database;
- `apps/server` and `apps/web` are the only application runtimes;
- supported deployment is Docker Compose with separate `migrate` and optional `backup` services;
- no phone-home, remote enrollment, license gate, fleet registry, or central privileged management channel;
- Smartup-managed edge/storage uses Cloudflare/R2, while self-hosters control providers and may use any compatible S3 target;
- multi-host HA and central observability are not bundled guarantees;
- migration history and superseded ADR bodies remain for traceability.

- [ ] **Step 2: Add explicit historical status markers without rewriting history**

Change only the status/dependency preamble of historical ADRs:

```markdown
**Статус:** Заменено ADR-0014 (2026-09-03)
```

for ADR-0004 and ADR-0007, and:

```markdown
**Статус:** Частично заменено ADR-0014 (2026-09-03)

> Историческое решение ниже сохраняется для трассируемости. Положения о
> Control Plane, fleet, лицензировании, Nomad, Consul и обязательном Vault
> больше не описывают поддерживаемый runtime.
```

for ADR-0001, ADR-0006, ADR-0008, ADR-0009, ADR-0010, and ADR-0011. Use the grammatically equivalent `Частично заменён` marker for ADR-0003. Do not edit decision-body history.

- [ ] **Step 3: Run the public-document contract**

```powershell
./scripts/docs/test-public-docs.ps1
```

Expected: ADR status failures disappear; the command remains red only because the technical specification/index and active-document rewrites are not complete.

- [ ] **Step 4: Commit the ADR transition**

```powershell
git add docs/adr
git commit -s -m "docs(adr): record unified open-source runtime"
```

---

### Task 4: Create the canonical Russian technical specification and documentation index

**Files:**

- Create: `docs/technical-specification.md`
- Create: `docs/README.md`
- Modify: `README.md:97-108`

**Interfaces:**

- Consumes: README product model, ADR-0014, Compose services, threat model, provider configuration, release gates, and stakeholder capacity/launch inputs.
- Produces: stable `FR-*`, `NFR-*`, and `AC-*` identifiers used by engineering, QA, release review, and later documentation.

- [ ] **Step 1: Write the technical-specification header and authority rules**

Start `docs/technical-specification.md` with:

```markdown
# Техническое задание SmartupCMS

**Версия:** 1.0

**Статус:** Базовая спецификация для подготовки релиза

**Дата:** 2026-09-03

**Горизонт запуска:** четыре месяца; точная календарная дата не утверждена

Этот документ является единым нормативным ТЗ. При расхождении документации
фактическое поведение подтверждается кодом и автоматическими контрактами, а
расхождение регистрируется как дефект документации до изменения требований.
```

- [ ] **Step 2: Document product scope and deployment model**

Include the following normative facts:

- product: self-hosted content and operations platform for one organization and many users;
- actors: installation operator, organization administrator, authorized employee, integration/provider, contributor/release maintainer;
- modules: identity/access, organization/users, tasks/comments/custom fields, files, search, notifications/webhooks/announcements, audit/security events, system administration;
- one organization per installation and separate database/storage per installation;
- managed estate planning input: up to approximately 100 installations, 500 registered users aggregate, 100 simultaneously active users aggregate, and 50 GB uploaded per month aggregate;
- per-installation peak distribution and a measured latency/SLO target are not confirmed and remain release-planning gaps;
- launch horizon: four months from the approved planning baseline, exact release date not confirmed;
- non-goals: Control Plane, multi-organization shared database, mandatory telemetry, remote enrollment, license enforcement, Nomad/Consul/Kubernetes distribution, bundled multi-host HA, and a forced cloud provider for self-hosters.

- [ ] **Step 3: Define functional requirements with stable identifiers**

Create tables containing at least these requirements and verification sources:

- `FR-AUTH-01..05`: login/logout, forced bootstrap-password change, hashed password/session/token storage, session revocation, CSRF protection;
- `FR-IAM-01..05`: users, companies/organization scope, roles, permissions, server-side enforcement;
- `FR-WORK-01..04`: task lifecycle, assignment, comments, custom fields/audited changes;
- `FR-FILE-01..07`: authorized upload/download/delete, 50 MiB application limit, metadata/hash, quarantine, fail-closed scanner in production, local/S3 storage, last-reference object deletion;
- `FR-SEARCH-01..03`: Typesense derived index, server-mediated queries, authorization-constrained results/rebuildability;
- `FR-COMM-01..05`: console defaults, optional SMTP/SMS/messenger, webhook allow-list/signing/retries, local announcements, no mandatory egress;
- `FR-ADMIN-01..04`: user/role/system administration, audit access, provider/storage configuration, sanitized backup status;
- `FR-OSS-01..03`: complete Apache-2.0 source, identical product for managed/self-hosted use, no runtime licensing callback.

Each row must contain: identifier, `Должна` requirement, code/config evidence path, and acceptance method.

- [ ] **Step 4: Define data and non-functional requirements**

Add:

- authoritative data in PostgreSQL; Typesense is derived and rebuildable;
- uploaded bytes in `local_disk` or S3-compatible storage; managed target R2;
- Flyway-only forward migrations through the separate `migrate` service;
- encrypted pre-migration backup, checksum, off-host age identity, default database-backup retention of 14 days, and separate object recovery;
- PII classes and operator-owned legal basis/retention/subject-request decisions;
- `NFR-SEC-*`: OWASP controls, least privilege, secret exclusion, TLS at edge, upload scanning, SSRF defense, signed release verification;
- `NFR-PERF-*`: acceptance load profile must be measured before release; p95 API/page goals are explicitly marked as unapproved release inputs rather than invented values;
- `NFR-REL-*`: health checks, fail-closed migration, graceful shutdown, documented rollback, no bundled cross-host HA;
- `NFR-OBS-*`: structured logs/metrics endpoints exist; alert transport, dashboards, and SLO are operator responsibilities until separately approved;
- `NFR-PORT-*`: `linux/amd64` and `linux/arm64` release images; Docker Engine 26+ and Compose v2.

- [ ] **Step 5: Define release acceptance criteria and open decisions**

Create measurable `AC-01..12` covering:

1. backend `mvn -B verify`;
2. web unit/typecheck/build;
3. Compose/unified/docs/hygiene/release/security contracts;
4. empty-database migration;
5. healthy clean Compose startup;
6. Chromium E2E critical journeys;
7. EICAR rejection and scanner-outage cleanup;
8. encrypted backup and restore drill;
9. S3/R2 file round-trip and deletion consistency;
10. role/IDOR negative tests;
11. signed image, SBOM, checksum, provenance verification;
12. installation-specific owners for SLO, retention, incident contacts, RPO/RTO, domain, provider region, and rollback decision.

State that unknown SLO, legal retention, production domains, incident ownership, and per-installation load distribution cannot be confirmed from the repository.

- [ ] **Step 6: Create the documentation index**

Create `docs/README.md` with four authority tiers:

1. requirements: `technical-specification.md`;
2. current decisions: ADR-0014 plus non-superseded ADRs;
3. engineering guidance: architecture and guidelines;
4. operations/security: `ops/`, RB-04, and threat model.

Explicitly say `audit/` is dated evidence, `docs/superpowers/` is implementation/design history, and neither overrides the canonical TЗ or current ADR. Link every active document by a valid relative path.

- [ ] **Step 7: Add entry links to README**

After the onboarding link in `README.md`, add links to the documentation index and technical specification. Keep the public README in English and do not duplicate the Russian TЗ.

- [ ] **Step 8: Run the link/status contract**

```powershell
./scripts/docs/test-public-docs.ps1
```

Expected: failures remain only in still-stale architecture/guideline/RB-04 documents; all newly created links resolve.

- [ ] **Step 9: Commit the canonical documentation**

```powershell
git add README.md docs/README.md docs/technical-specification.md
git commit -s -m "docs(product): establish canonical technical specification"
```

---

### Task 5: Rewrite active engineering guidance and migration runbook

**Files:**

- Modify: `docs/architecture/monorepo-structure.md`
- Modify: `docs/architecture/biruni-smartup-conventions.md`
- Modify: `docs/guidelines/database-migrations.md`
- Modify: `docs/guidelines/module-development-guide.md`
- Modify: `docs/guidelines/testing-strategy.md`
- Modify: `docs/runbooks/RB-04-migration-failure-triage.md`

**Interfaces:**

- Consumes: TЗ identifiers, ADR-0014, actual repository paths, Compose commands, Maven/npm/Playwright scripts, and current CI workflow.
- Produces: executable contributor and operator guidance with no retired identifiers or missing TRD/TZ links.

- [ ] **Step 1: Rewrite the monorepo map from actual paths**

Document exactly:

```text
apps/server/              Spring Boot modular monolith
apps/web/                 Angular SPA and NGINX image
libs/core-types/          shared value/error contracts
libs/platform-common/     shared backend infrastructure
libs/provider-spi/        storage/mail/SMS/messenger interfaces
deploy/compose/           production Compose bundle
deploy/images/            hardened image extensions
deploy/nginx/             production reverse proxy
e2e/                      Playwright and configuration/security tests
scripts/                  architecture, docs, dev, prod, release, security gates
docs/                     requirements, ADRs, engineering and operations docs
audit/                    dated evidence, never normative requirements
graphify-out/              current generated navigation outputs only
```

State dependency direction: feature modules may depend on shared kernel/common/provider SPI; shared libraries may not depend on application modules; browser calls only the server API; Typesense never authorizes access.

- [ ] **Step 2: Update Biruni/Smartup conventions**

Remove Control Plane prefixes, tables, and cache names. Retain only conventions observable in `apps/server`, migrations, and `apps/web`: package/module naming, `id`/`code`/audit columns, UTC timestamps, permission naming, API error shape, Angular feature/component naming, and provider SPI boundaries. Link naming rules to TЗ and ADR-0014.

- [ ] **Step 3: Rewrite database migration guidance for Compose**

Specify:

```powershell
docker compose run --rm migrate
```

for development and the documented production deploy script for releases. Require immutable Flyway files, expand/contract changes, transaction-safe DDL where PostgreSQL supports it, pre-migration encrypted backup for upgrades, schema readiness before server start, restore-based recovery for incompatible migrations, and a migration integration test. Remove Nomad job/ring instructions and ADR-0007 dependency.

- [ ] **Step 4: Update module development guidance**

Use `apps/server/src/main/java` and `apps/web/src/app` as the only application roots. Require controllers to validate/authorize and delegate, application services to own use cases/transactions, repositories/adapters to own I/O, and cross-module integration through explicit interfaces/events rather than internal package access. Include the exact verification commands from README.

- [ ] **Step 5: Update testing strategy from CI reality**

Document these exact gates:

```powershell
mvn -B verify
Push-Location apps/web; npm test; npm run typecheck; npm run build; Pop-Location
./scripts/architecture/test-unified-boundaries.ps1
./scripts/docs/test-public-docs.ps1
./scripts/docs/test-repository-hygiene.ps1
./scripts/release/verify-release.ps1
./scripts/prod/test-release-config.ps1
./scripts/prod/test-backup-status.ps1
Push-Location e2e; npm run test:config; npm run typecheck; npm run test:artifact-security; npm test; Pop-Location
```

Map unit, integration, architecture, configuration, security, and E2E layers to `AC-01..12`. Remove references to old application names and missing TRDs.

- [ ] **Step 6: Rewrite RB-04 as a current Compose incident procedure**

The runbook must include:

- trigger: `migrate` exits non-zero or schema readiness blocks `server`;
- immediate action: stop deployment, do not run manual destructive SQL, retain migration logs without secrets;
- diagnosis: `docker compose run --rm migrate`, PostgreSQL/Flyway schema-history inspection through an operator-controlled session, disk/connection/permission checks;
- recovery branches: fix configuration and retry an idempotent migration; deploy corrected forward migration; or restore the verified encrypted pre-migration backup and previous immutable image;
- validation: Flyway success, server readiness, critical smoke/E2E, audit/data count checks;
- evidence: incident timestamps, release digest, migration version, backup checksum, decision owner, recovery RPO/RTO outcome.

- [ ] **Step 7: Verify active docs contain no retired identifiers or broken links**

Run:

```powershell
./scripts/docs/test-public-docs.ps1
rg -n -i "apps/(instance|control-plane|web-instance|web-cp)|docker-compose\.fleet|migrate-cp|DWH_CP_|TRD-[0-9]|TZ-[0-9]" README.md docs/README.md docs/technical-specification.md docs/architecture docs/guidelines docs/ops docs/runbooks
```

Expected: contract PASS; `rg` returns no matches. Negative architectural language uses generic prose rather than retired path/config identifiers.

- [ ] **Step 8: Commit the active guidance**

```powershell
git add docs/architecture docs/guidelines docs/runbooks/RB-04-migration-failure-triage.md
git commit -s -m "docs(engineering): align guidance with unified runtime"
```

---

### Task 6: Publish a current repository health report and consolidate audit authority

**Files:**

- Create: `audit/health-check-2026-09-03.md`
- Preserve: `audit/*2026-09-03.md`
- Preserve and carefully merge: `audit/fixes/00-implementation-tracker.md`

**Interfaces:**

- Consumes: read-only inventory, current 2026-09-03 detailed audit reports, cleanup evidence, and repository contracts.
- Produces: one short health entry point that points to deeper current evidence without replacing it.

- [ ] **Step 1: Write the health report with evidence paths**

Include:

- executive assessment: unified open-source architecture is coherent but production release remains conditional;
- system map: Angular web, Spring modular monolith, PostgreSQL, Typesense, optional ClamAV/backup, Compose and CI;
- confirmed strengths: separate migrations, server-side authorization, file quarantine/scanning controls, SBOM/provenance/signing, current operations/threat docs;
- confirmed release gaps from current evidence only: unapproved measurable SLO/load thresholds, installation-specific privacy/retention owners, external monitoring/alert delivery, restore/object-recovery drill evidence, and exact production provider/domain decisions;
- repository hygiene finding: stale Control Plane/Nomad/Vault artifacts removed and guarded by CI;
- links to every current `audit/*2026-09-03.md` report and `audit/evidence/cto-audit-2026-09-03.md`;
- explicit statement that builds/tests were not executed during the health-audit inventory; verification results are recorded only after Task 7 runs them.

For every finding use: observation, risk, evidence path/line, smallest recommendation, effort S/M/L, priority P0/P1/P2.

- [ ] **Step 2: Update the implementation tracker without overwriting existing dirty edits**

Inspect the current diff first:

```powershell
git diff -- audit/fixes/00-implementation-tracker.md
```

Append a repository-hygiene row referencing the design, plan, TЗ, ADR-0014, both docs scripts, and the health report. Preserve every existing user-authored line and status.

- [ ] **Step 3: Validate all current audit links**

Run:

```powershell
$files = @('audit/health-check-2026-09-03.md') + @(Get-ChildItem audit -Filter '*2026-09-03.md' -File | ForEach-Object { $_.FullName })
foreach ($file in $files | Sort-Object -Unique) {
    if ((Get-Item $file).Length -eq 0) { throw "Empty audit file: $file" }
}
```

Expected: no empty current audit file.

- [ ] **Step 4: Commit only the health report and intentional tracker merge**

```powershell
git add audit/health-check-2026-09-03.md audit/fixes/00-implementation-tracker.md
git commit -s -m "docs(audit): record repository cleanup health"
```

Leave other pre-existing untracked current audit reports unstaged unless the user separately asks to publish them in the same change set.

---

### Task 7: Regenerate Graphify and run final verification

**Files:**

- Modify through generator: `graphify-out/graph.json`
- Modify through generator: `graphify-out/graph.html`
- Modify through generator: `graphify-out/GRAPH_REPORT.md`
- Modify through generator: `graphify-out/manifest.json`
- Modify through generator: `graphify-out/.graphify_labels.json`
- Modify through generator: `graphify-out/.graphify_labels.json.sig`

**Interfaces:**

- Consumes: final repository contents.
- Produces: current scoped knowledge graph and complete verification evidence.

- [ ] **Step 1: Update the graph after all content changes**

Run:

```powershell
graphify update .
```

Expected: current graph/report/manifest are regenerated. Cache and dated snapshot output may exist locally but must be ignored and untracked.

- [ ] **Step 2: Remove regenerated local-only cache/snapshot data after validating paths**

Resolve `graphify-out/cache`, `graphify-out/last_query_stamp`, and any direct child matching `graphify-out/\d{4}-\d{2}-\d{2}` to absolute paths. Verify every target starts with `D:\Claude\dwh\graphify-out\` and is not `graphify-out` itself, then remove each with native PowerShell `Remove-Item -LiteralPath ... -Recurse -Force`.

- [ ] **Step 3: Run fast documentation and architecture contracts**

```powershell
./scripts/docs/test-repository-hygiene.ps1
./scripts/docs/test-public-docs.ps1
./scripts/architecture/test-unified-boundaries.ps1
./scripts/release/verify-release.ps1
./scripts/prod/test-release-config.ps1
./scripts/prod/test-backup-status.ps1
```

Expected: all commands exit 0.

- [ ] **Step 4: Run application verification in proportion to risk**

```powershell
mvn -B verify
Push-Location apps/web
npm test
npm run typecheck
npm run build
Pop-Location
Push-Location e2e
npm run test:config
npm run typecheck
npm run test:artifact-security
Pop-Location
```

Expected: every command exits 0. Full browser E2E is not required for documentation-only behavior unless the running stack or UI files changed during this work; pre-existing UI/E2E changes remain outside the cleanup commits.

- [ ] **Step 5: Prove protected state and repository cleanliness**

Run:

```powershell
Get-ChildItem -LiteralPath backups -Recurse -File | Measure-Object
docker compose ps --format json
docker volume ls --filter name=smartupcms
git status --short --branch
git ls-files graphify-out/cache graphify-out/2026-08-29 graphify-out/2026-08-30 graphify-out/2026-08-31 graphify-out/2026-09-01
```

Expected: backups still exist; Compose services/volumes remain; no generated cache/snapshot path is tracked; Git status contains only explicitly preserved pre-existing work plus current Graphify outputs awaiting commit.

- [ ] **Step 6: Review the complete change set**

```powershell
git diff --check
git diff --stat HEAD~6..HEAD
git status --short
```

Expected: no whitespace errors, no backup/data deletion, no unexpected source or runtime change.

- [ ] **Step 7: Commit current Graphify outputs only**

```powershell
git add graphify-out/graph.json graphify-out/graph.html graphify-out/GRAPH_REPORT.md graphify-out/manifest.json graphify-out/.graphify_labels.json graphify-out/.graphify_labels.json.sig
git commit -s -m "docs(graph): refresh repository knowledge map"
```

- [ ] **Step 8: Record final evidence in the health report if results differ from the audit-time statement**

If every command in Steps 3-5 passed, append a dated `Post-implementation verification` section listing the exact commands and exit status, then commit:

```powershell
git add audit/health-check-2026-09-03.md
git commit -s -m "docs(audit): record cleanup verification"
```

If any command failed, record only the observed failure in the handoff; do not claim the repository cleanup complete and do not alter unrelated code to hide it.
