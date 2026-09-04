# Documentation audit — 2026-09-03

## Coverage map

The repository includes README, contributor/security/support/governance files,
ADRs, architecture/deployment/operations/maintenance/rollback runbooks, threat
model and a measurable launch checklist. This is above a typical pre-1.0
baseline. The remaining risk is contradiction and missing executable contracts,
not absence of documents.

| Audience / need | State | Evidence / gap |
|---|---|---|
| New developer | Mostly covered | README and contributing docs exist; exact Node version can still differ from local environment without a preflight. |
| Local operator | Covered | Compose and operations runbooks exist. |
| Production operator | Partial | Checklist/runbooks exist; target Hetzner/Cloudflare/R2 values, owners and evidence do not. |
| Security responder | Partial | `SECURITY.md` and threat model exist; installation contacts, response owner and retention are blank. |
| API integrator | Weak | Manual `OpenApiController.java` documents only a subset and retains old product naming. |
| Data/DB operator | Partial | 21 Flyway migrations and rollback guidance exist; schema catalog/data classification and combined DB+objects restore evidence are incomplete. |

## Findings

| Observation | Risk | Evidence | Minimal action / acceptance criterion | Effort | Priority |
|---|---|---|---|---|---|
| ADR-0009/0010/0011 still present Accepted Control Plane/Nomad/Vault assumptions after unified open-source pivot. | Contradictory architecture becomes accidental scope expansion. | `docs/adr/ADR-0009*`, `ADR-0010*`, `ADR-0011*`; current `README.md:14`. | Mark superseded/partially superseded, link the replacing decision, keep historical rationale. | S | P1 |
| OpenAPI is manual, partial and branded “Smartup DWH Platform Instance API.” | External consumers cannot discover or safely version all supported endpoints. | `OpenApiController.java:14-118`; about 118 mappings found. | Generate/test contract and publish supported/experimental endpoint policy. | M | P1 |
| Threat model claims scoped task/file checks, while ADR-0013 explicitly describes organization-wide task reads/default `ALL` and inspected paths have no object predicate. | Operators may assume narrower IDOR controls than the accepted/current behavior. | `threat-model.md:40`; `ADR-0013-data-scope.md:16-23,130-132`; inspected task/file read paths. | State exact org-wide versus scoped semantics per entity and link the corresponding cross-role tests. | S | P0 conditional / P1 documentation |
| Launch checklist explicitly says repository lacks target evidence/SLO. | A checklist alone can be mistaken for completion. | `production-launch-checklist.md:95-98`. | Store one sanitized evidence bundle per installation/release with owner, date, SHA/digest and links to tests. | S | P0 installation |
| Historical audit/spec files are useful but lack a single current index. | Old findings and removed fleet design compete with current source of truth. | `audit/*`, `docs/superpowers/specs/*`. | Add “current / superseded / historical” index and link only current reports from README/operations. | S | P2 |

## Required minimum additions

- Current architecture ADR: one organization per install, no product control
  plane, supported topology, single-replica invariant, scale-out prerequisites.
- Versioned API contract and compatibility/deprecation policy.
- Installation annex: domains/IPs, data owner/legal basis/retention, SLO/RPO/RTO,
  alert/on-call/security contacts, Cloudflare/R2/host controls.
- Restore evidence template covering PostgreSQL plus object bytes.
- Capacity/load report template bound to dataset, host profile, SHA and image
  digests.
- Known limitations: single-host availability, optional integrations, file
  scanner requirement, per-process state and supported upgrade window.
