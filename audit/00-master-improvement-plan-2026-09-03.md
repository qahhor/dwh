# SmartupCMS CTO audit and strategic improvement plan — 2026-09-03

Audit target: `main` at
`df84019886aa47c504e5562253dd1ef842b3d96b`.

Detailed reports: [architecture](architecture-2026-09-03.md),
[performance](performance-2026-09-03.md), [security](security-2026-09-03.md),
[code quality and debt](code-quality-2026-09-03.md),
[testing](testing-2026-09-03.md), [DevOps](devops-2026-09-03.md),
[documentation](documentation-2026-09-03.md),
[evidence and limitations](evidence/cto-audit-2026-09-03.md).

Effort scale: S = up to 1 person-week, M = 1–3 person-weeks, L = 3–8
person-weeks. Estimates include implementation and focused tests, but not
external procurement, compliance certification or customer coordination.

## 1. Executive summary

1. **Release readiness: NO for production; CONDITIONAL for a repository release candidate.**
2. The product is a sound Java/Spring modular monolith with Angular, PostgreSQL, Typesense and S3/local storage; microservices are not justified at forecast load.
3. The strongest assets are executable layer/cycle rules, 206 backend + 68 frontend + 9 browser tests, hardened Compose and signed/SBOM release design.
4. P0 code blocker: the 50 MB file contract is absent from active Spring/Nginx ingress configuration, so valid uploads can fail before business validation.
5. P0 reliability blocker: file upload holds a DB transaction across storage and antivirus I/O; 20 concurrent slow uploads can consume the full configured pool.
6. P0 security blocker: malware scanning defaults to optional in production configuration.
7. Current ADR intentionally makes task reads organization-wide/default `ALL`; file read scope and cross-role evidence are incomplete, so launch must explicitly accept or narrow this contract.
8. Capacity for 100 concurrent users and 50 GB/month uploads is not measured; no representative load/soak evidence exists.
9. Hetzner/Cloudflare/R2 security, direct-origin closure, alerts and combined DB+object restore are not present as target evidence.
10. Current remote CI, Trivy result and immutable SemVer release artifact were not independently confirmed.
11. ISO/IEC 27001 certification/compliance cannot be inferred from code; the ISMS/ownership/evidence layer is incomplete.
12. Visible release-critical debt is estimated at 11–17 person-weeks; the launch remains achievable in four months if P0 work starts now and scope stays fixed.
13. Strategic priority is depth: close file/data/recovery/observability contracts, then optimize measured SQL/auth paths; do not add a control panel, broker or microservices.

## Product and system context

- One installation serves one organization (`README.md:14`). About 100 clients
  therefore imply approximately 100 isolated deployments, not one shared
  multi-tenant database.
- Forecast supplied by the owner: about 500 users, 100 concurrently active,
  50 GB uploaded per month, launch in four months. Per-installation versus fleet
  aggregation was not specified; this plan uses 100 concurrent as a conservative
  per-install acceptance test.
- Managed installations intend Hetzner + Cloudflare security/edge + Cloudflare
  R2. Customer-managed installations may use another compatible storage. No
  actual target deployment evidence was available.
- Critical flows evidenced by tests: authentication, navigation, roles/users,
  project→task→comment, file lifecycle, notifications/announcements and system
  status. Payments and a broker were not found.

## 2. Release blockers (P0)

| ID | Observation → risk | Evidence | Concrete action and verification | Effort |
|---|---|---|---|---|
| P0-FILE-1 | Declared 50 MB limit is not configured at active ingress → legitimate files can fail and edge/app behavior diverges. | `MfFileService.java:24-46`; no multipart config in `application.yml`; no limit in `apps/web/nginx.conf:1-55`; only an optional outer config has 100M. | Set one limit/error contract across Cloudflare, web Nginx and Spring. Through the production origin: ~49 MB succeeds, >50 MB returns 413/stable problem detail, partial data/temp objects = 0. | S |
| P0-FILE-2 | Upload holds a transaction/DB connection across storage upload, AV scan and copy → pool starvation and broad outage under slow storage/scanner. | `MfFileService.java:43-150`; scanner read timeout 60s `application.yml:107-114`; Hikari max 20 `:18`. | Separate external I/O from a short atomic quota/metadata finalization; add compensation. In 20×49 MB upload test plus interactive traffic, sustained pool pending=0 and interactive p95 stays within approved SLO. | M |
| P0-FILE-3 | Malware scanner is optional by default → production can distribute malicious files while healthy. | `FileScannerStartupCheck.java:19-36`; `application.yml:107-108`; Compose `:30`; threat model `:44`. | Production validation requires scanner=true and healthy, or disables uploads. EICAR rejected, outage fails closed, quarantine cleaned, no download before clean verdict. | S/M |
| P0-AUTH-1 | ADR-0013 intentionally grants organization-wide/default `ALL` task visibility; file reads are also broad in code but no separate file policy was found, while threat model claims scoped checks → conditional IDOR/data exposure if the release expects narrower isolation. | `docs/adr/ADR-0013-data-scope.md:16-23,130-132`; `MsTaskController.java:136-178`; `MfFileController.java:75-85`; threat model `:40`. | Product explicitly accepts org-wide task/file reads or approves narrower predicates; cross-role direct-API tests must prove the chosen contract. No launch with an ambiguous file policy. | M |
| P0-REL-1 | No representative performance baseline → 100-active-user capacity and 50 GB/month flow are assumptions. | No load artifact; checklist `production-launch-checklist.md:31-33,75-86`. | Synthetic dataset + interactive/upload/degraded/4h soak scenarios; approve p95/p99/error/pool/temp/storage thresholds and archive SHA/host/digest evidence. | M |
| P0-OPS-1 | Actual Hetzner/Cloudflare/R2 boundary is unverified → origin bypass, public ports, TLS/WAF/bucket errors. | Target IaC/evidence not found; Compose only proves loopback default `:107-108`; checklist `:21-33`. | Production-like staging; external port/TLS/header/WAF/rate/upload/direct-origin tests; R2 policy/CORS/lifecycle/versioning review. Only approved HTTPS path is reachable. | M |
| P0-OPS-2 | DB backup does not cover file objects and no combined isolated restore is evidenced → unrecoverable customer content. | `architecture-overview.md:57-58,88-104`; checklist `:35-47`. | Restore PostgreSQL plus local/R2 objects on a destroyed disposable target, validate checksums and download, record/approve RPO/RTO and key custody. | M |
| P0-OPS-3 | Metrics endpoint exists, but no collection/alerts/log aggregation/on-call proof → incidents can remain invisible. | `application.yml:55-68`; `architecture-overview.md:101-104`. | Golden-signal dashboard, external synthetic, backup/scanner/cert/dead-letter/capacity alerts; each P0 alert reaches named on-call and has exercised runbook. | M |
| P0-SC-1 | Current immutable artifact/security pipeline is not proven end-to-end → reviewed source may differ from deployed bytes. | Remote run unavailable; current Trivy scan incomplete; release workflow `release.yml:78-199`. | Required CI green, current Gitleaks/Trivy green, stable SemVer tag, independent digest/Cosign/provenance/SBOM/checksum verification, deploy by digest. | S |
| P0-GOV-1 | PII/security/SLO/incident owners and target retention decisions are absent → no accountable production operation or ISO-aligned evidence. | `threat-model.md:7-10,48-69`; launch checklist `:49-61,84-98`. | Named Product/Engineering/Security/Operations owners approve SLO, RPO/RTO, retention, support access, vulnerability SLA and go/no-go evidence. | S/M |

## 3. Risk register

Probability/impact are qualitative because no incident history, revenue per
client or production metrics were supplied.

| Risk | Probability | Impact | Leading indicator | Mitigation / owner |
|---|---|---|---|---|
| Upload endpoint rejects normal files | High | High | 413 before app error; low successful object sizes | Align all ingress limits and boundary E2E / Engineering |
| DB pool exhaustion during uploads | Medium/High | Critical | Hikari pending, request p95, scanner/R2 latency | Short transaction + reservation, load/fault test / Engineering |
| Malicious file redistribution | Medium | Critical | Scanner missing/unhealthy, quarantine age | Fail-closed scanner and alerts / Security+Operations |
| Task/file data overexposure | Unknown until policy | Critical | Cross-role negative failure, direct ID access | Data-scope decision and repository predicates / Product+Security |
| DB/object loss after host failure | Medium | Critical | Backup age, restore age, object mismatch | Off-host protection and combined drill / Operations |
| Cloudflare/origin/storage misconfiguration | Medium | Critical | Direct-origin reachable, unexpected port/bucket public | IaC/external scan/config review / Operations+Security |
| Capacity collapse at 100 active users | Medium | High | p95/p99, DB pending, temp/heap/disk saturation | Representative load/soak and measured sizing / Engineering |
| Silent auth/production incident | High | High | missing centralized lookup/alert, auth anonymous spikes | Preserve structured correlation; add log collection, metrics and P0 paging / Operations |
| Duplicate/lost side effects on scale-out | Medium if replicas>1 | High | idempotency PENDING age, duplicate reminders | One-replica invariant; leases/shared claims before scale-out / Architecture |
| Search/analytics slowdown with data growth | Medium | Medium/High | sequential scans, query time, Typesense lag | EXPLAIN-driven indexes, keyset, bounded cache / Data/Backend |
| Supply-chain artifact mismatch/vulnerability | Medium | High | unsigned digest, stale scan DB, failed history scan | Immutable signed artifact gate / Release owner |
| Managed-fleet configuration drift | High at 100 installs | High | version skew, manual changes, failed upgrades | Separate IaC/inventory/upgrade automation / Platform Ops |
| PII retained/leaked in logs/support | Medium | High | raw DB errors, missing retention jobs | Redaction test, retention/access policy / Security+Legal |
| Frontend change velocity degrades | High | Medium | hot-file churn, review lead time, escaped UI defects | Incremental component extraction and changed-code typing/tests / Frontend |

## 4. Prioritized initiatives and ROI

No revenue, churn, downtime cost or engineering rate was provided. Financial
numbers therefore use the transparent scenario from the debt report:
USD 4,000 per person-week, sensitivity USD 2,500–6,000. ROI is expressed as a
break-even condition rather than invented savings.

| Initiative | Cost scenario | Business return / break-even | Priority |
|---|---:|---|---|
| File ingress + fail-closed scanner | 1–2 pw / $4k–8k | Pays back if it prevents one blocked pilot, malware incident, or more than 1–2 pw support/rework. Directly protects core 50 GB/month flow. | P0 |
| Upload transaction/quota/object integrity | 2–3 pw / $8k–12k | Pays back if it avoids one pool outage/data-loss remediation or >2–3 pw incident work. Enables stated concurrency without premature infrastructure. | P0 |
| Data-scope policy + negative tests | 1–2 pw / $4k–8k | A single customer data exposure can exceed the full cost; also removes sales/security-review blocker. | P0 |
| Target observability/load/restore | 5–7 pw / $20k–28k | Break-even if combined reduction in outage/data-loss risk and launch firefighting exceeds 5–7 pw. Required to sell an operational promise. | P0 |
| Immutable release/security proof | <=1 pw / <=$4k | Very high leverage: binds supportable release to reviewed bytes and reduces supply-chain ambiguity. | P0 |
| Auth/SQL/idempotency optimization | 3–5 pw / $12k–20k | Execute only after metrics except correctness leases. Pays if it delays a larger host/DB tier or removes recurring latency/support. | P1 |
| Managed-installation IaC | 4–8 pw / $16k–32k | Break-even when saved provisioning/update/recovery work exceeds 4–8 engineer-weeks. At 100 installs, even one manual hour per install per recurring change is 100 hours/change; actual hours must be measured. | P1 before scale |
| UI decomposition + API contract/types | 6–10 pw / $24k–40k | Lower immediate release ROI; schedule around hot modules when it measurably reduces review lead time/escaped defects. | P1/P2 |

Portfolio estimate: release-critical work 11–17 pw / $44k–68k scenario;
total visible debt 24–40 pw / $96k–160k. These are remediation-budget
ranges, not booked loss. Do not sum “avoided incidents” without real incident,
revenue and churn data.

## 5. Work plan: first 2–4 weeks

### Week 1 — stabilization and security

- Align Cloudflare/web/Spring file limits and stable 413 response.
- Make production scanner configuration fail closed; add startup/config gate.
- Approve task/file/analytics visibility matrix and retention/security owners.
- Redact raw database error logging; instrument swallowed auth failures.
- Preserve the tested request-correlation/MDC lifecycle; add a log-capture smoke
  test that finds one request by the returned `traceparent`.
- Resolve Gitleaks historical test-fixture classification and update moderate
  dev dependency; obtain repeatable current scans.

Exit: P0-FILE-1/3 closed; data-scope decision signed; immutable CI security jobs
green; no secret/PII in negative-test logs.

### Week 2 — file correctness and critical regression depth

- Refactor upload into staged external I/O plus short transactional reservation/
  finalization and compensation.
- Make quota atomic; serialize/reference-count physical object lifecycle.
- Add 49 MB, over-limit, EICAR, scanner-outage, interrupted upload and two-thread
  quota/upload/delete tests.
- Implement approved task/file scope and cross-role direct-API tests.

Exit: P0-FILE-2 and P0-AUTH-1 closed; failure injection leaves no inaccessible
metadata or orphan quarantine objects; normal API remains responsive during
concurrent uploads.

### Week 3 — observability, capacity and recovery

- Deploy production-like Hetzner/Cloudflare/R2 staging by versioned automation.
- Ship redacted structured logs while preserving existing request correlation;
  scrape metrics and route P0 alerts to named on-call.
- Create representative synthetic dataset; run 100-user interactive and upload
  contention tests; tune only evidenced bottlenecks.
- Perform isolated PostgreSQL + object restore and record RPO/RTO.

Exit: dashboards/alerts/restore evidence bound to SHA/digests; 4-hour soak meets
approved SLO; direct-origin and non-web ports unavailable externally.

### Week 4 — release contour rehearsal

- Fix only measured SQL/auth bottlenecks; add EXPLAIN-backed indexes/keyset.
- Run clean install, oldest-supported upgrade, intentional failure and rollback.
- Produce stable SemVer multi-arch images; independently verify signature,
  provenance, SBOM, checksums; deploy by digest.
- Complete go/no-go checklist with named Product/Engineering/Security/Operations
  approvals.

Exit: repository RC and first production-like installation are reproducible and
all P0 gates are green. Unfinished P1 items have owner/date and cannot weaken a
P0 acceptance criterion.

## 6. Four-month launch roadmap

| Period | Outcome | Main work | Decision gate |
|---|---|---|---|
| Weeks 1–4 | Release foundation | P0 plan above | No open code/security/recovery/target P0 |
| Weeks 5–8 | Operational depth | Auth query/write reduction, idempotency lease/cleanup, SQL plans/indexes, OpenAPI contract, first IaC module, nightly browser/a11y | 100-user baseline repeatable; one-click/reviewed staging rebuild |
| Weeks 9–12 | Pilot evidence | 2–3 isolated pilot installations, upgrade/rollback/restore drills, 24h soak, on-call rehearsal, vulnerability response and support workflow | Pilot SLO and recovery accepted; no recurring Sev-1/Sev-2 defect |
| Weeks 13–14 | Release candidate freeze | Only blocker fixes, penetration test, compatibility/migration matrix, signed RC, customer/operator docs | Security and operations sign-off |
| Weeks 15–16 | Staged launch | Canary customer cohort, monitored expansion, daily risk review, post-launch rollback window | Formal GO; expansion pauses automatically on SLO/security/recovery breach |

Fleet automation should be built for managed operations, not embedded as a new
customer-facing Control Panel. Start with host/network/volume/Cloudflare/R2
modules, inventory, immutable version/digest and upgrade/restore evidence.

## 7. Definition of Done for production release

- [ ] Reviewed immutable SHA merged; required remote CI and DCO are green.
- [ ] Current backend/frontend/typecheck/build/config/docs/E2E suites are green
      with zero release-critical skip or flaky quarantine.
- [ ] Stable SemVer artifacts, digests, checksums, SBOM, provenance and Cosign
      signature are independently verified; deployment uses digests.
- [ ] 49 MB upload succeeds end-to-end; >50 MB has a controlled 413; partial
      upload leaves no metadata/temp/quarantine leak.
- [ ] Scanner is required/healthy; EICAR and scanner-outage tests are green.
- [ ] Upload external I/O does not hold DB transaction; concurrency test proves
      atomic quota/object lifecycle and no DB-pool starvation.
- [ ] Data-scope matrix is approved and all task/file/search/analytics operations
      have direct-API cross-role negative coverage.
- [ ] Clean install, supported upgrade, failed migration and rollback are green.
- [ ] Production-like target exposes only approved HTTPS; Cloudflare direct-
      origin, WAF/rate limits and R2 policies are verified.
- [ ] Encrypted DB and object bytes restore in isolation; measured RPO/RTO and
      retention are accepted.
- [ ] 100-active-user load, upload contention, dependency degradation, 4h and
      final 24h soak meet written latency/error/saturation SLOs.
- [ ] P0 alerts reach named on-call; runbooks for app, DB, storage, scanner,
      backup, certificate and dead letters are exercised.
- [ ] Runtime secrets use approved storage/rotation; logs/support artifacts pass
      secret/PII redaction tests.
- [ ] Installation threat/PII annex, incident process, vulnerability SLA and
      Product/Engineering/Security/Operations GO are recorded.

## 8. Questions and missing data

1. Are 500 users / 100 active / 50 GB per month targets for **each installation**
   or the entire fleet? This changes host sizing and test datasets materially.
2. Are all tasks and files intentionally visible to every user with the relevant
   functional permission, or must access be project/member/owner scoped?
3. What numerical SLA/SLO is sold: monthly availability, p95/p99 latency,
   maintenance windows and error budget?
4. What contractual RPO/RTO and retention apply to PostgreSQL, objects, logs,
   audit partitions and backups?
5. What exact Hetzner region/SKU/OS/disk/backup network and Cloudflare plan,
   WAF/rate/origin-certificate policy are selected?
6. Is R2 versioning/object lock/lifecycle required, and who owns bucket/key
   recovery? Database backup alone is insufficient.
7. Which scanner will production operate, where is it hosted, how is its
   signature database updated and monitored?
8. Who are named Product, Engineering, Security/Privacy and 24×7 Operations
   owners, and what is the vulnerability/incident response SLA?
9. What revenue per installation, churn sensitivity, support hours and cost of
   downtime/data loss should replace the scenario ROI model?
10. Which API endpoints are public/stable for third-party integrations, and what
    compatibility/deprecation window is promised?
11. Which browsers/accessibility standard are contractual: WCAG 2.2 AA, Chromium
    only, or Chromium+Firefox+WebKit?
12. What branch protection/required checks apply to `main`, and where will
    release signatures/attestations be independently verified?

## Final recommendation

Proceed with the four-month plan, but declare **NO-GO today**. Do not change the
macro-architecture and do not split into microservices. The shortest path to a
sellable enterprise release is to make the existing monolith demonstrably safe,
recoverable and measurable: file pipeline first, then authorization contract,
target infrastructure, restore, observability and load evidence. Reassess
horizontal scaling only after the baseline produces a measured limit.
