# Documentation audit — 2026-09-02

## Status

The active public documentation contract passes 15 required files and 19
Markdown files. README links onboarding, architecture, deployment, operations,
maintenance, rollback, threat model, security and support (`README.md:93-108`).
Architecture explicitly states single organization, private services, data
flows, backup/object separation and known single-host limits
(`architecture-overview.md:7-60,98-104`).

## Findings

| Observation | Risk | Evidence | Minimal recommendation | Effort | Priority |
|---|---|---|---|---|---|
| New-developer run/build/test path and supported product topology are documented and executable. | Low repository-onboarding risk. | `README.md`, `docs/onboarding.md`, `scripts/docs/test-public-docs.ps1`. | Keep docs gate required. | S | P2 |
| Deployment, webhook policy, backup/restore and rollback runbooks exist. | Operators still lack target-specific commands/owners. | `deployment-guide.md`, `operations-runbook.md`, `maintenance-guide.md`, `rollback.md`. | Create one Hetzner/Cloudflare installation annex with exact DNS/firewall/secret/restore owners. | S | P0 installation |
| Threat model and PII categories exist, but retention/legal basis and incident contacts are deliberately unresolved. | Compliance decisions may be mistaken for implemented controls. | `docs/security/threat-model.md:7-9`; launch checklist `:59-61`. | Fill and approve the installation annex; keep unknowns explicit. | S | P0 installation |
| API contract is controller/code-led; no complete generated OpenAPI coverage was confirmed. | Integrators may rely on stale shapes, as happened with notifications. | Notification mismatch fixed in `notification.service.ts`; partial API docs only. | Add contract tests/generated schema for externally supported endpoints, starting notifications/webhooks/files. | M | P1 |
| Historical audit/Fleet documents remain in `audit/` as evidence. | Readers can confuse superseded Control Plane decisions with supported runtime. | `audit/evidence/fleet-foundation-cp-contract-2026-09-01.md`; tracker marks it Superseded. | Add a prominent archive/superseded index; do not delete historical evidence. | S | P2 |

