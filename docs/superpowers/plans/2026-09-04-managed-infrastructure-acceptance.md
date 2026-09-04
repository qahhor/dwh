# Managed Infrastructure Release-Acceptance Plan

**Goal:** Close the locally implementable parts of P0-REL-1 and P0-OPS-1..3, then produce a deterministic evidence procedure for the real Hetzner + Cloudflare + R2 environment.

**Boundary:** Smartup-managed installations use Hetzner compute, Cloudflare edge protection, and Cloudflare R2 object storage. Client-owned installations may use any compatible infrastructure/provider. Repository tests may emulate S3 locally, but production acceptance is not complete until real target credentials, DNS, host, alerts, and recovery storage are supplied.

## Task 1: Automated acceptance harness

- [x] Add a non-secret target environment template with explicit required variables.
- [x] Add fail-closed external and host preflight checks for HTTPS origin, direct-origin denial, TLS/security headers, public ports, R2 private domains/CORS/lifecycle, scanner health, alert endpoints, and five running digest references. Cloudflare R2 does not implement `PutBucketVersioning`; acceptance records `NOT_APPLICABLE` and requires a separate encrypted recovery bucket.
- [x] Emit machine-readable evidence manifests bound to git SHA, five image digests, host profile, timestamp, and redacted target identifiers.

## Task 2: Capacity and failure scenarios

- [x] Add reproducible 100-active-user load profiles for interactive reads/writes and 20 concurrent near-limit uploads.
- [x] Add automated scanner/database/backup failure drills, an installation-specific R2 outage procedure, and a four-hour soak profile.
- [x] Enforce approved thresholds for p95/p99, error rate, Hikari pending, heap, disk/temp growth, scanner/R2 latency, and orphan objects; reject monitoring windows that do not cover the complete k6 run.

## Task 3: Combined recovery

- [x] Extend the restore drill to restore PostgreSQL plus local/S3-compatible object bytes into an isolated target.
- [x] Validate row counts, object inventory/checksums, sampled downloads, RPO, and RTO.
- [ ] Execute and retain the three negative proofs for missing/wrong age key, missing object backup, and partial restore. The implementation validates both archives before starting isolated PostgreSQL, but execution evidence is still target input.

## Task 4: Target execution and release evidence

- [ ] Execute the harness through the external Cloudflare HTTPS hostname on the real Hetzner staging host.
- [ ] Exercise WAF/rate-limit rules, R2 policies, scanner outage, alerts to named on-call, backup failure, rollback, and combined restore.
- [ ] Verify release digest, Cosign signature, provenance, SBOM, and checksums independently before deployment by digest.
- [x] Keep every target-only item `UNVERIFIED` until captured evidence exists; no local emulation may mark it complete.

## Current status

The local harness, contracts, metrics, load profiles, failure drills, encrypted
object backup, isolated combined restore, digest deployment overrides, and
target-side release verifier are implemented. All Task 4 checks and the three
negative recovery executions remain `UNVERIFIED` because this checkout has no
Hetzner host, production domain, Cloudflare/R2 credentials, alert receiver,
approved thresholds, load-user tokens, or release tag evidence.
