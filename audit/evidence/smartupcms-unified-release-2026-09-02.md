# SmartupCMS unified release verification — 2026-09-02

**Candidate SHA:** `5013ebd7d59d038de7948146c7b792ae24628d45`

**Branch:** `codex/unified-open-source`

**Verification window:** 2026-09-02 08:05–08:25 UTC

## Reproducible evidence

| Gate | Command / artifact | Result |
|---|---|---|
| Backend | Maven 3.9.12 + Temurin 25, `mvn -B verify` | PASS: 194 tests, 0 failures, 0 errors |
| Frontend | Node 24.15.0, `npm ci && npm test && npm run typecheck && npm run build` | PASS: 26 files / 68 tests; typecheck and production build exit 0 |
| Public docs | `scripts/docs/test-public-docs.ps1` | PASS: 15 required files / 19 Markdown files |
| Release config | `scripts/prod/test-release-config.ps1`; dev/prod `docker compose ... config --quiet` | PASS |
| Upgrade | `scripts/prod/test-v018-upgrade.ps1 -ProjectName smartupcms-v018-proof` | PASS: encrypted V018 backup, V019 migration, five healthy services, unified-origin HTTP 200 |
| Default egress | `scripts/security/test-no-default-egress.ps1 -ObservationSeconds 65` | PASS: no external DNS/IP traffic observed |
| Workflow lint | `rhysd/actionlint:1.7.7` | PASS |
| Secrets, tree | `zricethezav/gitleaks:v8.28.0 dir` | PASS: ~85.76 MB, no leaks |
| Secrets, history | `gitleaks git --log-opts="--all --full-history"` | PASS: 170 commits / ~156.01 MB, no leaks |
| Dependencies | `aquasec/trivy:0.74.0 fs`, HIGH/CRITICAL, ignore-unfixed | PASS: 0 findings in 4 Maven manifests and 2 npm lockfiles |
| Runtime images | `scripts/security/scan-runtime-images.ps1` | PASS: 0 HIGH/CRITICAL in server, web, backup, PostgreSQL and Typesense images |
| Clean install | exact Compose project `smartupcms-release-e2e`; volumes removed, images rebuilt, empty DB migrated | PASS: V001→V019, PostgreSQL/server/Typesense/web healthy, `/`=200, `/healthz`=200 |
| Browser E2E | `scripts/dev/test-e2e.ps1 -SkipInstall` | PASS: config 3/3, artifact security, typecheck, browser 9/9 |

The clean stack remains available locally at `http://127.0.0.1:54210`. It is a
development Compose deployment, not evidence for Hetzner, Cloudflare, TLS, WAF,
off-host recovery, capacity, or SLA readiness.

## Remote state

The commit was pushed successfully and `origin/codex/unified-open-source`
resolved to the same SHA. The public GitHub API reported no workflow run for the
branch. This is consistent with `.github/workflows/ci.yml:5-9`: push runs are
limited to `main`; other branches are checked through `pull_request`. No PR was
created because that external collaboration action was not part of the request.

## Known verification limitations

- No release SemVer tag, GHCR digest, cosign signature, provenance or uploaded
  SBOM exists for this branch SHA; those are produced by the release workflow.
- No actual Hetzner/Cloudflare environment, secrets, DNS, certificate, WAF or
  external port scan was available.
- Database upgrade backup was verified; isolated restore of target data and
  uploaded-object recovery were not verified.
- No representative 100-concurrent-user/50-GB-per-month load or soak evidence
  exists.
