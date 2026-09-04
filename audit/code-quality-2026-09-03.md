# Code quality and technical-debt audit — 2026-09-03

The codebase has strong baseline automation and a pragmatic modular-monolith
shape. The highest debt is not “old technology”; it is concentrated complexity
in the frontend, mixed transaction/infrastructure boundaries, incomplete API
contract generation and missing measured quality thresholds.

## Findings

| Observation | Risk | Evidence | Minimal action / acceptance criterion | Effort | Priority |
|---|---|---|---|---|---|
| Backend layering and cycle rules are executable. | Low current risk; architecture remains reviewable as modules grow. | `ModularArchitectureTest.java:39-109`; 29 controllers/services and 30 repositories. | Keep rules required; add module-specific rules only for observed violations. | S | P2 |
| Four Angular components exceed 1000 lines; tasks is about 2907 lines. | Change collision, regressions and high onboarding/review cost; weak single responsibility. | Source metrics in `audit/evidence/cto-audit-2026-09-03.md`; `apps/web/src/app/features/tasks/tasks.component.ts`. | Extract facade/state and presentational sections incrementally when touched. First target tasks/users/roles; no redesign. Each extraction keeps behavior/E2E green. | L | P1 |
| Explicit `any` remains in shared UI/API/form code. | Contract drift reaches runtime and makes refactoring unsafe. | Repository TypeScript search; representative shared selection/API call sites. | Establish baseline count, forbid new explicit `any` in changed code, replace high-traffic shared types first. | M | P2 |
| Manual OpenAPI contains only a small subset of roughly 118 REST mappings and keeps old “Smartup DWH Platform Instance” naming. | Integrators and generated clients cannot rely on the contract; docs drift silently. | `OpenApiController.java:14-118`; route/annotation inventory in evidence. | Generate OpenAPI from controllers/DTOs or test manual spec against route inventory; rename to SmartupCMS; version compatibility policy. | M | P1 |
| Idempotency and file lifecycle correctness require temporal/concurrent reasoning inside broad services. | Subtle crash/race bugs are expensive to review and reproduce. | `IdempotencyService.java:38-76`; `MfFileService.java:43-216`. | Introduce small explicit state machines/value types and concurrency integration tests before further feature work. | M | P1 |
| No coverage floor or mutation testing gate was found. | Green tests can coexist with untested critical branches; trend is invisible. | Maven/Angular config and CI contain tests but no JaCoCo/coverage threshold. | Publish report first; enforce changed-code floors only on auth/RBAC/storage/idempotency/outbox after stable baseline. | M | P1 |
| Current local Node is 24.14.0 while repository requires 24.15.0. | Contributor builds fail before tests, creating avoidable onboarding/support cost. | `.node-version`; `apps/web/package.json` engines; audit evidence. | Add preflight and one documented version-manager/container command; acceptance: clean machine bootstrap follows README without version ambiguity. | S | P2 |
| Current and historical architecture documents coexist without a clear superseded index. | Engineers can implement removed Control Plane/Fleet decisions. | `docs/adr/ADR-0009*`, `ADR-0010*`, `ADR-0011*` versus `README.md:14`. | Mark statuses accurately and link one current architecture source of truth. | S | P1 |

## Debt inventory and financial model

Financial values are planning scenarios because actual salary, incident loss,
support cost and roadmap delay cost were not supplied. Model:

- one person-week (pw) = 5 engineering days;
- scenario blended fully-loaded cost = **USD 4,000/pw**;
- sensitivity = **USD 2,500–6,000/pw**;
- opportunity/incident loss is excluded, so this is a lower-bound remediation
  budget, not a business-loss estimate.

| Debt class | Scope | Estimate | Scenario cost | What breaks / value of payment |
|---|---|---:|---:|---|
| Reliability/security | upload ingress, transaction, scanner, quota/object races | 3–5 pw | $12k–20k | Prevents broken uploads, pool outage, quota bypass, unsafe files and orphan/missing objects. |
| Access/security | task/file scope policy, negative tests, log redaction, auth telemetry | 2–3 pw | $8k–12k | Prevents data exposure and makes auth incidents detectable. |
| Release/operations | target edge, restore, SLO/load/alerts, immutable artifact proof | 6–9 pw | $24k–36k | Converts locally green code into an operable/recoverable production installation. |
| Architecture/data | idempotency lease, reminder claim, SQL/index/pagination work | 3–5 pw | $12k–20k | Removes retry/race failure modes and predictable data-growth bottlenecks. |
| Maintainability | UI decomposition, API contract, types, ADR cleanup | 6–10 pw | $24k–40k | Reduces regression/review time; enables safer product delivery after launch. |
| Managed fleet ops | reusable IaC/inventory/upgrade evidence for 100 installations | 4–8 pw | $16k–32k | Avoids manual configuration drift without adding a product control plane. |

Known **release-critical** debt: approximately **11–17 pw**, scenario
**$44k–68k** (sensitivity $27.5k–102k). Total visible debt including
post-launch maintainability/fleet automation: approximately **24–40 pw**,
scenario **$96k–160k** (sensitivity $60k–240k). Ranges deliberately overlap
where a fix closes more than one category; portfolio planning must de-duplicate
work items.

## Debt repayment policy

- Reserve 35–40% capacity for P0/P1 hardening through the release candidate.
- After P0 closure, use a 20% quality budget and “boy scout” extraction in the
  touched UI module; do not pause all product work for a large rewrite.
- No new architectural platform, cache, broker or microservice without a
  measured bottleneck and an ADR with operating-cost owner.
- Track lead time, escaped defects, flaky tests, support hours and hot-file
  change frequency monthly. If a debt item does not move one of these metrics or
  a release risk, lower its priority.

