# Managed Infrastructure Release-Acceptance Plan

**Goal:** Close the locally implementable parts of P0-REL-1 and P0-OPS-1..3, then produce a deterministic evidence procedure for the real Hetzner + Cloudflare + R2 environment.

**Boundary:** Smartup-managed installations use Hetzner compute, Cloudflare edge protection, and Cloudflare R2 object storage. Client-owned installations may use any compatible infrastructure/provider. Repository tests may emulate S3 locally, but production acceptance is not complete until real target credentials, DNS, host, alerts, and recovery storage are supplied.

## Task 1: Automated acceptance harness

- [ ] Add a non-secret target environment template with explicit required variables.
- [ ] Add fail-closed preflight checks for HTTPS origin, direct-origin denial, TLS/security headers, public ports, R2 bucket policy/CORS/versioning/lifecycle, scanner health, and alert endpoints.
- [ ] Emit a machine-readable evidence manifest bound to git SHA, image digest, host profile, timestamp, and redacted target identifiers.

## Task 2: Capacity and failure scenarios

- [ ] Add reproducible 100-active-user load profiles for interactive reads/writes and 20 concurrent near-limit uploads.
- [ ] Add degraded R2/scanner/database scenarios and a four-hour soak profile.
- [ ] Enforce approved thresholds for p95/p99, error rate, Hikari pending, heap, disk/temp growth, scanner/R2 latency, and orphan objects.

## Task 3: Combined recovery

- [ ] Extend the restore drill to restore PostgreSQL plus local/S3-compatible object bytes into an isolated target.
- [ ] Validate row counts, object inventory/checksums, sampled downloads, RPO, and RTO.
- [ ] Prove missing/wrong age key, missing object backup, and partial restore fail closed.

## Task 4: Target execution and release evidence

- [ ] Execute the harness through the external Cloudflare HTTPS hostname on the real Hetzner staging host.
- [ ] Exercise WAF/rate-limit rules, R2 policies, scanner outage, alerts to named on-call, backup failure, rollback, and combined restore.
- [ ] Verify release digest, Cosign signature, provenance, SBOM, and checksums independently before deployment by digest.
- [ ] Keep every target-only item `UNVERIFIED` until captured evidence exists; no local emulation may mark it complete.
