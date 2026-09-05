# Release hardening — implementation roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute this plan sequentially. Reinspect each boundary before implementation; use test-driven-development for defects.

**Goal:** Close release risks without adding product capabilities or replacing the modular monolith.

**Architecture:** One organization per installation; existing server authorization and provider boundaries remain authoritative.

**Tech Stack:** Java 25 / Spring Boot / JdbcClient / PostgreSQL / Flyway; Angular; Docker Compose; GitHub Actions.

**Spec:** `docs/technical-specification.md`, ADR-0008, ADR-0013, ADR-0014, `docs/guidelines/testing-strategy.md`. This is an execution plan, not a new specification or certification statement.

## Global Constraints

- Preserve unrelated changes and local audit drafts; do not promote drafts into requirements.
- No new microservices, features, destructive data cleanup, production changes or deployment from this plan alone.
- No commit/push in the initial execution batch. Tests use disposable databases, not the running application's database.
- Every item requires a reproduced defect or verified missing contract, minimal patch, regression tests and fresh relevant gates. Estimates are provisional, not a delivery promise.
- Finish each numbered package before moving to the next. Split externally blocked acceptance from code completion explicitly.
- Refresh Graphify after source changes; do not publish graphs generated from unrelated dirty files.

## Ordered backlog

| Order | Package | Narrow implementation boundary / evidence to recheck | Acceptance | Estimate |
|---|---|---|---|---|
| 1 | I-01 — task export security | `ReportController`, `ReportService`, existing `MdScopeService.filterForTasks`; detailed plan below | Scope matrix on real PostgreSQL for CSV and XML aliases; text formula neutralization; backend verify | 2–4 engineer-days |
| 2 | I-04 — secret-safe idempotency | `IdempotencyFilter`, credential response callers and `idempotency_keys` | No secret-bearing response cached; explicit supported-operation contract; crash, TTL and body-limit tests; assess existing sensitive rows without dumping/deleting them | 3–6 days |
| 3 | I-03 — trusted client IP and bounded limiter | Rate-limit filter, authentication/CSRF chain and NGINX ingress | Spoofed forwarded IP cannot bypass limit; hard bound on buckets; cookie/Bearer/CSRF matrix | 2–4 days |
| 4 | I-02 — database least privilege | Production Compose, initializer, migrations and audit maintenance | Runtime cannot superuser/DDL/TRUNCATE/disable audit; migration and partition maintenance still work; fresh and upgrade tests | 3–5 days |
| 5 | I-05 — recoverable deployment | Deploy, backup/restore scripts and persistent volumes | Retained-volume fresh-start safety; known previous digests; consistent DB/object restore with measured recovery | 5–8 days |
| 6 | I-06 — release artifact gates | CI/release workflows, scanners and history-secret fixture | Every published digest tested/scanned; signature/provenance/SBOM verified; no mandatory test skipped | 3–5 days |
| 7 | I-07 — reliable search synchronization | Transactional task mutations, search indexer and reconciliation | No index before commit; durable bounded retry; restart/reconcile convergence | 3–5 days |
| 8 | I-08 — measured database/concurrency improvements | Authentication SQL/activity writes, duplicate indexes, task update contract | Before/after SQL evidence; forward-only index migration; conflicting task updates cannot silently overwrite | 3–6 days |
| 9 | I-09 — capacity and operations evidence | Resource limits, export/upload paths, load profile and monitoring | Approved representative 100-active-user workload; bounded memory; SLO/load/soak and restore evidence on target infrastructure | 6–10 days |
| 10 | I-10 — final release readiness | API/docs drift, targeted quality gates and operating annex | Documentation matches tested release; named privacy/retention/incident owners; all P0 closed | 3–5 days |

Each package after I-01 gets its own file-level implementation plan after source inspection. In particular, database grants, idempotent endpoint semantics and backup consistency must not be guessed from this roadmap.

## Timeline and business control

- Weeks 1–2: I-01 → I-04 → I-03; prioritize exposed data and credentials.
- Weeks 3–4: I-02 and start I-05; protect data and recovery.
- Weeks 5–8: complete I-05, then I-06; prove the releasable artifact.
- Weeks 9–12: I-07 → I-08 → I-09; optimize against measurements.
- Weeks 13–16: complete I-09/I-10, acceptance and defect buffer.

Total provisional effort: 33–58 engineer-days, 42–73 with 25% uncertainty reserve. Calendar depends on actual staffing and external access. Financial cost = measured hours × agreed fully-loaded rate; percentage ROI is not asserted without incident/support cost data.

## Execution state

- [x] Recheck export finding, callers, ADR-0013 and test conventions; independent read-only boundary investigation.
- [x] Create branch `codex/release-hardening` in current checkout; preserve existing dirty work.
- [x] Write [I-01 detailed plan](2026-09-05-task-export-security.md).
- [x] Complete and verify I-01: 69 new export cases; full backend verify 334 tests, zero failures/errors/skips; independent security review completed. See detailed plan evidence.
- [ ] Inspect and write the next detailed plan for I-04; then execute in order.

## External acceptance inputs (not blockers for I-01)

Target staging access, domain/Cloudflare configuration, R2 bucket/recovery policy, staffing and release owner, approved SLO/RPO/RTO and privacy/retention are not established by a local code change. Ask for missing inputs one at a time when their package requires them. Never mark target deployment or ISO compliance accepted from local tests alone.

## Publication instruction — 2026-09-05

The user subsequently requested commit, push and deployment, and explicitly selected `main` for further work. The initial no-publication restriction above describes the implementation batch only. Changes were transferred to `main`; delete only branches whose tips are already contained in published `main`. Preserve unrelated dirty Graphify files, local audit drafts and output artifacts. Production acceptance remains separate from updating the existing local Docker installation.
