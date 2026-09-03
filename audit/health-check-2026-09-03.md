# Repository health check — 2026-09-03

## Executive assessment

SmartupCMS now has a coherent unified open-source architecture: one Angular web
application fronts one Spring modular monolith, with PostgreSQL as the source of
truth and Typesense as a derived index. Production release remains conditional.
The repository defines the intended controls and acceptance criteria, but the
installation-specific SLO, privacy, monitoring, recovery, and provider evidence
listed below is not yet approved or demonstrated.

This report is a self-contained, tracked evidence entry point. Local audit
working papers that are not committed are non-authoritative and are not needed
to substantiate the findings below.

## Verification boundary

No builds or tests were executed during this health-audit inventory. The results
of repository, application, browser, deployment, and Graphify verification must
be recorded only after Task 7 runs them. Any earlier result remains historical
evidence for its audited SHA, not a fresh Task 6 verification result.

## System map

```text
Browser -> operator-managed edge -> Angular/Nginx -> Spring modular monolith
                                                     |-> PostgreSQL
                                                     |-> Typesense (derived)
                                                     |-> local or S3/R2 objects
                                                     |-> ClamAV (optional local/dev;
                                                         required in production)
Backup job (optional local/dev; required in production) -> PostgreSQL + encrypted local or operator-configured off-host target
CI ------------------------------------------------> code, docs, security, release
```

The supported deployment is Docker Compose with a one-shot `migrate` service
and one installation serves one organization. The root local/development
topology may omit ClamAV and the scheduled backup profile; the supported
production bundle requires fail-closed ClamAV and encrypted backup services.
Cloudflare/R2 is the intended managed-provider choice, while self-hosters may
select another compatible S3 provider. See
[ADR-0014](../docs/adr/ADR-0014-unified-open-source-runtime.md):21-40 and the
[production Compose bundle](../deploy/compose/docker-compose.prod.yml):30-36,90-98,158-217.

## Findings

| Observation | Risk | Evidence path/line | Smallest recommendation | Effort | Priority |
|---|---|---|---|---|---|
| The unified runtime boundary is explicit and matches the current Angular, Spring, PostgreSQL, Typesense, Compose, and CI shape. | Future documentation or implementation drift could reintroduce a second runtime or unsupported fleet dependency. | [`docs/adr/ADR-0014-unified-open-source-runtime.md`](../docs/adr/ADR-0014-unified-open-source-runtime.md):21-40; [`pom.xml`](../pom.xml); [`scripts/architecture/test-unified-boundaries.ps1`](../scripts/architecture/test-unified-boundaries.ps1):8-58. | Keep ADR-0014 and the unified-boundary contract authoritative; require a new ADR for any runtime-boundary change. | S | P1 |
| Separate Flyway migration, server-side authentication/permission enforcement, and quarantine-before-publish/scanner controls are defined in current code-facing contracts. | Treating documented controls as release evidence could hide configuration or negative-test gaps that still require acceptance evidence. | [`docs/technical-specification.md`](../docs/technical-specification.md):59-62,84,102-103,148; [`docs/ops/architecture-overview.md`](../docs/ops/architecture-overview.md):43-61. | Preserve these controls and require their stated negative/failure acceptance evidence before production sign-off. | S | P0 |
| CI and release workflows define SBOM generation, provenance, checksums, and Cosign signing, and current operations and threat-model documents identify the runtime trust boundaries. | A strong pipeline design can be mistaken for proof that the current release artifact and each installation were verified. | [`.github/workflows/release.yml`](../.github/workflows/release.yml):151-199; [`scripts/release/verify-release.ps1`](../scripts/release/verify-release.ps1); [`docs/ops/architecture-overview.md`](../docs/ops/architecture-overview.md):7-105; [`docs/security/threat-model.md`](../docs/security/threat-model.md):17-49. | Bind the Task 7 and release results to an immutable SHA/image digest and keep installation evidence separate from repository design. | S | P0 |
| Measurable SLO and load thresholds are proposed but not approved, and the committed tree contains no representative load/soak result. | Capacity for the planned 100 active users and upload volume is unknown, so latency and saturation promises are not supportable. | [`audit/fixes/P-01-performance-baseline.md`](fixes/P-01-performance-baseline.md):5-18; [`docs/technical-specification.md`](../docs/technical-specification.md):170-171,216-226; [`docs/ops/production-launch-checklist.md`](../docs/ops/production-launch-checklist.md):30-33,88-90. | Name the SLO owner, approve p95/p99/error/saturation gates, then run the specified representative load and soak profile. | M | P0 |
| Installation-specific privacy roles, legal basis, exact retention, deletion/hold process, and accountable owners are unresolved. | Personal or confidential data may be retained, accessed, or deleted inconsistently, and compliance claims would lack accountable evidence. | [`docs/security/threat-model.md`](../docs/security/threat-model.md):51-79; [`docs/technical-specification.md`](../docs/technical-specification.md):149-150,216-227; [`docs/ops/production-launch-checklist.md`](../docs/ops/production-launch-checklist.md):55-63. | Complete and approve one installation annex covering every data class, owner, legal basis, retention, and deletion/hold rule. | M | P0 |
| Metrics and request correlation exist, but external collection, dashboards, alert delivery, and named on-call evidence are absent from the supported bundle. | Production incidents, stale backups, scanner failures, or capacity exhaustion may remain undetected or unowned. | [`apps/server/src/main/resources/application.yml`](../apps/server/src/main/resources/application.yml); [`docs/ops/architecture-overview.md`](../docs/ops/architecture-overview.md):73-105; [`docs/technical-specification.md`](../docs/technical-specification.md):183-188,229. | Configure the smallest external metrics/log path and prove each P0 synthetic alert reaches the named on-call owner. | M | P0 |
| Encrypted PostgreSQL backup is documented, but the committed release evidence contains no current isolated drill that restores both the database and object bytes. | A successful database-only restore can leave customer files unavailable or inconsistent, with unknown RPO/RTO. | [`docs/ops/maintenance-guide.md`](../docs/ops/maintenance-guide.md):27-77; [`docs/guidelines/testing-strategy.md`](../docs/guidelines/testing-strategy.md):89-102; [`docs/technical-specification.md`](../docs/technical-specification.md):208-209. | Run one destructive disposable-target DB-plus-objects restore, verify checksums/downloads, and approve the measured RPO/RTO. | M | P0 |
| Exact production domain, host/network controls, edge/direct-origin policy, and R2 region/bucket controls remain installation decisions without target evidence. | Origin bypass, unintended service exposure, TLS/WAF mismatch, or incorrect object policy can defeat repository-level controls. | [`docs/ops/deployment-guide.md`](../docs/ops/deployment-guide.md):13-18,133-153; [`docs/security/threat-model.md`](../docs/security/threat-model.md):17-49; [`docs/technical-specification.md`](../docs/technical-specification.md):216-228. | Freeze the target domain/provider matrix and capture a sanitized external port, TLS, WAF, direct-origin, and R2 policy check. | M | P0 |
| Approved stale Control Plane, Nomad, Vault, legacy audit, Playwright-state, and Graphify-cache artifacts were removed in `c6a1181`; CI now guards obsolete paths, active-document terminology/status, and links across all tracked Markdown. | Generated or superseded material could return and again compete with the supported runtime and current documentation. | [`docs/superpowers/specs/2026-09-03-repository-cleanup-and-documentation-refresh-design.md`](../docs/superpowers/specs/2026-09-03-repository-cleanup-and-documentation-refresh-design.md):34-64; [`scripts/docs/test-repository-hygiene.ps1`](../scripts/docs/test-repository-hygiene.ps1):6-79; [`scripts/docs/test-public-docs.ps1`](../scripts/docs/test-public-docs.ps1):4-189; [`.github/workflows/ci.yml`](../.github/workflows/ci.yml):68-78. | Keep both documentation contracts required in CI and bind successful results to an immutable remote SHA before release. | S | P1 |

## Evidence authority

The committed evidence for this report is the primary code/configuration and
tracked documentation linked in each finding. Nine local 2026-09-03 audit
working papers remain unpublished; they may inform later reconciliation but do
not define requirements, release status, or evidence in a clean checkout.

## Release position

The cleanup and documentation consolidation improve repository health, but they
do not change the release gate: production remains conditional until the P0
findings above and the installation-specific acceptance evidence are closed by
named owners.

## Post-implementation verification — 2026-09-03

Task 7 verified the cleanup locally at commit `178e26a` after the original
content changes were complete. The following commands reached exit status 0:

- `graphify update .` — rebuilt 3,944 nodes, 10,148 edges, and 244
  communities. The six pre-update generated outputs were preserved under the
  ignored Task 7 plan workspace before regeneration. For unchanged community
  signatures, 142 of 142 labels remained byte-for-byte identical; no stable
  community label was silently replaced.
- `./scripts/docs/test-repository-hygiene.ps1`
- `./scripts/docs/test-public-docs.ps1` — checked 18 required files and the
  then-configured 35-document set; the final-review contract now checks every
  Git-tracked Markdown file.
- `./scripts/architecture/test-unified-boundaries.ps1`
- `./scripts/release/verify-release.ps1`
- `./scripts/prod/test-release-config.ps1`
- `./scripts/prod/test-backup-status.ps1`
- `& 'C:\Users\abdukahhor\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9\bin\mvn.cmd' -B verify`
  — 214 tests across tested reactor modules, with 0 failures, 0 errors, and 0
  skipped; reactor build success.
- In a clean `node:24.15.0-bookworm-slim` container populated only with
  `apps/web` manifests, configuration, and source,
  `npm ci && npm test && npm run typecheck && npm run build` — 26 test files
  and 68 tests passed; typecheck and production build passed.
- In a separate clean `node:24.15.0-bookworm-slim` container populated only
  with `e2e` manifests, configuration, scripts, support, and tests,
  `npm ci && npm run test:config && npm run typecheck && npm run test:artifact-security`
  — 3 configuration tests passed, typecheck passed, and the intentional
  Playwright failure produced no sentinel-secret leak. Chromium and its Linux
  runtime dependencies were installed only inside this disposable container;
  host `node_modules` was neither bound nor copied.
- `Get-ChildItem -LiteralPath backups -Recurse -File | Measure-Object` — 11
  files remained (1,483,491 bytes total); `Test-Path -LiteralPath '.env'`
  returned `True`.
- `docker compose ps --format json` — four services remained running and
  healthy.
- `docker volume ls --filter name=smartupcms` — eight matching volumes
  remained present.
- `git status --short --branch`
- `git ls-files graphify-out/cache graphify-out/2026-08-29 graphify-out/2026-08-30 graphify-out/2026-08-31 graphify-out/2026-09-01`
  — no tracked generated cache or dated snapshot path was returned.
- `git diff --check`
- `git diff --stat 3fd5a9cf5d152f1a44d70de791d95036e7927030..HEAD`

Warnings did not change these exit results: Graphify reported that its installed
skill metadata was older than package 0.9.51 and recommended an optional label
refresh; npm reported one moderate frontend dependency vulnerability and
Angular deprecation/focusability warnings; Maven/JDK emitted future native
access and `Unsafe` warnings. The first disposable E2E setup lacked the
Playwright browser executable; after installing the pinned Chromium runtime,
the complete E2E command sequence above was rerun from `npm ci` and passed.
These repository checks do not close the installation-specific P0 release
conditions in this report.
