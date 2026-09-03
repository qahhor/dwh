# Repository health check — 2026-09-03

## Executive assessment

SmartupCMS now has a coherent unified open-source architecture: one Angular web
application fronts one Spring modular monolith, with PostgreSQL as the source of
truth and Typesense as a derived index. Production release remains conditional.
The repository defines the intended controls and acceptance criteria, but the
installation-specific SLO, privacy, monitoring, recovery, and provider evidence
listed below is not yet approved or demonstrated.

This report is a short navigation entry point. It does not replace the detailed
2026-09-03 audits or close their code-level findings.

## Verification boundary

No builds or tests were executed during this health-audit inventory. The results
of repository, application, browser, deployment, and Graphify verification must
be recorded only after Task 7 runs them. Earlier results cited by the detailed
audits remain historical evidence for their audited SHA, not a fresh Task 6
verification result.

## System map

```text
Browser -> operator-managed edge -> Angular/Nginx -> Spring modular monolith
                                                     |-> PostgreSQL
                                                     |-> Typesense (derived)
                                                     |-> local or S3/R2 objects
                                                     |-> optional ClamAV
Optional backup job -------------------------------> PostgreSQL + off-host target
CI ------------------------------------------------> code, docs, security, release
```

The supported deployment is Docker Compose with a one-shot `migrate` service
and optional `backup`; one installation serves one organization. Cloudflare/R2
is the intended managed-provider choice, while self-hosters may select another
compatible S3 provider. See
[the architecture audit](architecture-2026-09-03.md):7-30 and
[ADR-0014](../docs/adr/ADR-0014-unified-open-source-runtime.md):21-39.

## Findings

| Observation | Risk | Evidence path/line | Smallest recommendation | Effort | Priority |
|---|---|---|---|---|---|
| The unified runtime boundary is explicit and matches the current Angular, Spring, PostgreSQL, Typesense, Compose, and CI shape. | Future documentation or implementation drift could reintroduce a second runtime or unsupported fleet dependency. | [`audit/architecture-2026-09-03.md`](architecture-2026-09-03.md):7-39; [`docs/adr/ADR-0014-unified-open-source-runtime.md`](../docs/adr/ADR-0014-unified-open-source-runtime.md):21-39. | Keep ADR-0014 and the unified-boundary contract authoritative; require a new ADR for any runtime-boundary change. | S | P1 |
| Separate Flyway migration, server-side authentication/permission enforcement, and quarantine-before-publish/scanner controls are defined in current code-facing contracts. | Treating documented controls as release evidence could hide configuration or negative-test gaps already described in the detailed audits. | [`docs/technical-specification.md`](../docs/technical-specification.md):59-62,84,102-103,148; [`docs/ops/architecture-overview.md`](../docs/ops/architecture-overview.md):43-61. | Preserve these controls and require their stated negative/failure acceptance evidence before production sign-off. | S | P0 |
| CI and release workflows define SBOM generation, provenance, checksums, and Cosign signing, and current operations and threat-model documents identify the runtime trust boundaries. | A strong pipeline design can be mistaken for proof that the current release artifact and each installation were verified. | [`audit/devops-2026-09-03.md`](devops-2026-09-03.md):5-11,26; [`.github/workflows/release.yml`](../.github/workflows/release.yml):151-199; [`docs/ops/architecture-overview.md`](../docs/ops/architecture-overview.md):7-105; [`docs/security/threat-model.md`](../docs/security/threat-model.md):17-49. | Bind the Task 7 and release results to an immutable SHA/image digest and keep installation evidence separate from repository design. | S | P0 |
| Measurable SLO and load thresholds are proposed but not approved, and no representative load/soak result was found. | Capacity for the planned 100 active users and upload volume is unknown, so latency and saturation promises are not supportable. | [`audit/performance-2026-09-03.md`](performance-2026-09-03.md):3-8,23-54; [`docs/technical-specification.md`](../docs/technical-specification.md):170-171,216-226. | Name the SLO owner, approve p95/p99/error/saturation gates, then run the specified representative load and soak profile. | M | P0 |
| Installation-specific privacy roles, legal basis, exact retention, deletion/hold process, and accountable owners are unresolved. | Personal or confidential data may be retained, accessed, or deleted inconsistently, and compliance claims would lack accountable evidence. | [`audit/security-2026-09-03.md`](security-2026-09-03.md):12-18,32,50-60; [`docs/technical-specification.md`](../docs/technical-specification.md):149-150,216-227. | Complete and approve one installation annex covering every data class, owner, legal basis, retention, and deletion/hold rule. | M | P0 |
| Metrics and request correlation exist, but external collection, dashboards, alert delivery, and named on-call evidence are absent. | Production incidents, stale backups, scanner failures, or capacity exhaustion may remain undetected or unowned. | [`audit/devops-2026-09-03.md`](devops-2026-09-03.md):24-25,31-43; [`docs/technical-specification.md`](../docs/technical-specification.md):183-188,229. | Configure the smallest external metrics/log path and prove each P0 synthetic alert reaches the named on-call owner. | M | P0 |
| Encrypted PostgreSQL backup is documented, but no current isolated drill restores both the database and object bytes. | A successful database-only restore can leave customer files unavailable or inconsistent, with unknown RPO/RTO. | [`audit/devops-2026-09-03.md`](devops-2026-09-03.md):23; [`audit/testing-2026-09-03.md`](testing-2026-09-03.md):20,32-34; [`docs/technical-specification.md`](../docs/technical-specification.md):208-209. | Run one destructive disposable-target DB-plus-objects restore, verify checksums/downloads, and approve the measured RPO/RTO. | M | P0 |
| Exact production domain, Hetzner host/network controls, Cloudflare edge/direct-origin policy, and R2 region/bucket controls remain installation decisions without target evidence. | Origin bypass, unintended service exposure, TLS/WAF mismatch, or incorrect object policy can defeat repository-level controls. | [`audit/devops-2026-09-03.md`](devops-2026-09-03.md):13-15,21-22; [`audit/security-2026-09-03.md`](security-2026-09-03.md):33; [`docs/technical-specification.md`](../docs/technical-specification.md):216-228. | Freeze the target domain/provider matrix and capture a sanitized external port, TLS, WAF, direct-origin, and R2 policy check. | M | P0 |
| Approved stale Control Plane, Nomad, Vault, legacy audit, Playwright-state, and Graphify-cache artifacts were removed in `c6a1181`; CI now guards obsolete paths, active-document terminology/status, and relative links. | Generated or superseded material could return and again compete with the supported runtime and current documentation. | [`docs/superpowers/specs/2026-09-03-repository-cleanup-and-documentation-refresh-design.md`](../docs/superpowers/specs/2026-09-03-repository-cleanup-and-documentation-refresh-design.md):34-64; [`scripts/docs/test-repository-hygiene.ps1`](../scripts/docs/test-repository-hygiene.ps1):6-79; [`scripts/docs/test-public-docs.ps1`](../scripts/docs/test-public-docs.ps1):4-129; [`.github/workflows/ci.yml`](../.github/workflows/ci.yml):68-78. | Keep both documentation contracts required in CI and let Task 7 record their fresh results. | S | P1 |

## Current audit set

- [Master improvement plan](00-master-improvement-plan-2026-09-03.md)
- [Architecture](architecture-2026-09-03.md)
- [Code quality and technical debt](code-quality-2026-09-03.md)
- [DevOps and release readiness](devops-2026-09-03.md)
- [Documentation](documentation-2026-09-03.md)
- [Performance and scalability](performance-2026-09-03.md)
- [Security](security-2026-09-03.md)
- [Testing strategy](testing-2026-09-03.md)
- [CTO audit evidence and limitations](evidence/cto-audit-2026-09-03.md)

## Release position

The cleanup and documentation consolidation improve repository health, but they
do not change the release gate: production remains conditional until the P0
findings in the current audit set and the installation-specific acceptance
evidence above are closed by named owners.

## Post-implementation verification — 2026-09-03

Task 7 verified the cleanup in the existing `main` checkout after the content
changes were complete. The following commands reached exit status 0:

- `graphify update .` — rebuilt 3,944 nodes, 10,148 edges, and 244
  communities. The six pre-update generated outputs were preserved under the
  ignored Task 7 plan workspace before regeneration. For unchanged community
  signatures, 142 of 142 labels remained byte-for-byte identical; no stable
  community label was silently replaced.
- `./scripts/docs/test-repository-hygiene.ps1`
- `./scripts/docs/test-public-docs.ps1` — checked 18 required files and 35
  Markdown files.
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
