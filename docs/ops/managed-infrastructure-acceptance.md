# Smartup-managed infrastructure acceptance

**Version:** 1.0

**Updated:** 2026-09-05

This runbook is the release gate for installations operated by SmartupCMS on
Hetzner compute, Cloudflare edge protection, and private Cloudflare R2 object
storage. It does not restrict client-owned installations to those providers.

The repository supplies deterministic checks; it does not contain production
credentials or target evidence. Until every target phase below has a passing
evidence file for the same release digest, the installation remains **NO-GO**.

Operator workstation prerequisites are PowerShell 7, Git, Docker Compose,
`curl`, AWS CLI v2, `age`, PostgreSQL `pg_restore`, `tar`, authenticated GitHub
CLI, and `cosign`. Run the contract check before using the procedures:

```powershell
./scripts/acceptance/test-managed-acceptance.ps1
```

## 1. Evidence boundary

Run every command from a clean checkout of the release commit. Store generated
JSON outside source control in an access-controlled incident/evidence system.
Each manifest binds the result to UTC time, git SHA, immutable image digest,
profile, and hashed target identifiers. Never attach `.env`, API tokens, API
token files, cookies, object contents, database dumps, or alert webhook URLs.

Copy the non-secret template from the release bundle and provide secret values
only through the process environment. The release workflow pre-fills all five
accepted `image@sha256` references in both production and acceptance templates:

```powershell
Copy-Item deploy/acceptance/.env.managed.example managed-staging.env
$env:CLOUDFLARE_API_TOKEN = '<secret-manager-value>'
$env:AWS_ACCESS_KEY_ID = '<application-bucket-test-key>'
$env:AWS_SECRET_ACCESS_KEY = '<application-bucket-test-secret>'
```

On the Hetzner host, set the five `*_IMAGE` variables in `.env.production` to
those exact references, then run the read-only host check:

```powershell
./scripts/acceptance/invoke-managed-host-check.ps1 `
  -ConfigFile ./managed-staging.env -EnvFile ./.env.production `
  -EvidenceDirectory /secure-evidence/release-vX.Y.Z
```

It checks the rendered Compose boundary, file modes, R2/scanner settings,
container health, internal readiness, and each running container's configured
digest reference. A tag-only managed deployment fails this gate.

The Cloudflare token needs read-only access to zone settings, DNS, rulesets,
and R2 bucket configuration. The R2 key must be a dedicated, time-limited key
limited to the application bucket. The preflight writes only below the
configured `acceptance/` prefix and deletes its test object.

## 2. Edge, host, R2, scanner, and alert preflight

From a network outside the Hetzner host:

```powershell
./scripts/acceptance/invoke-managed-preflight.ps1 `
  -ConfigFile ./managed-staging.env `
  -EvidenceDirectory D:/secure-evidence/release-vX.Y.Z
```

The command fails closed unless all of these controls pass:

- public `/healthz` and SPA respond over certificate-validated HTTPS;
- HSTS, CSP, MIME sniffing, frame, referrer, permissions, and `cf-ray` headers
  are present;
- resolving the public hostname directly to the Hetzner origin cannot return a
  successful application response;
- configured database, server, management, search, and HTTP origin ports are
  unreachable from the external runner;
- the DNS record is proxied, SSL is Full (strict), minimum TLS is 1.2+, Always
  Use HTTPS is enabled, and both managed WAF and rate-limit phases contain
  enabled rules;
- the R2 bucket is private (`r2.dev` disabled, no enabled custom domain), has
  no browser CORS rules, has the approved lifecycle rule, and passes a private
  put/head/get/SHA-256/delete round-trip;
- a distinct recovery bucket exists, scanner health responds, and the labelled
  alert drill reaches the on-call path.

Cloudflare documents that R2 buckets are private by default and exposes public
access through managed or custom domains. Both are checked through the API:
<https://developers.cloudflare.com/r2/buckets/public-buckets/>. R2's S3 API
does **not** implement `PutBucketVersioning`; versioning is therefore recorded
as `NOT_APPLICABLE`, never as a pass. The compensating control is the encrypted
object archive in a different recovery bucket plus a successful combined
restore. See the official compatibility table:
<https://developers.cloudflare.com/r2/api/s3/api/>.

## 3. Capacity acceptance

Create at least 100 staging-only API tokens with the same least-privilege role
used by the expected workload. Store them outside the repository:

```json
[
  {"token":"token-for-user-001"},
  {"token":"token-for-user-002"}
]
```

Approve every blank threshold in `managed-staging.env`. Values are an explicit
business/SLO decision; the repository deliberately provides no invented
defaults. Capture the full test-window maxima/minima from the monitoring stack
using `deploy/acceptance/runtime-metrics.example.json` as the schema. The
collector and dashboard URL must identify the source; null values fail. Start
the collector before k6 and have it atomically publish the JSON after the test;
the runner waits for it and rejects any window that does not cover the complete
k6 interval.

Run only on a disposable or dedicated staging installation:

```powershell
./scripts/acceptance/run-capacity.ps1 `
  -ConfigFile ./managed-staging.env `
  -UsersFile D:/secure-input/load-users.json `
  -RuntimeMetricsFile D:/secure-input/runtime-metrics.json `
  -Profile all `
  -AcknowledgeTargetLoad
```

The runner pins k6 by OCI digest and executes:

1. 100 active users performing scoped task/file/analytics reads and periodic
   task writes for the approved interactive duration.
2. 20 simultaneous uploads of a generated `50 MiB - 64 KiB` valid-magic PDF.
3. A four-hour 100-user soak.

k6 enforces p95, p99, error-rate, and check thresholds. The runner then enforces
Hikari pending, heap, minimum disk, temporary-space growth, scanner p95, R2 p95,
and orphan-object thresholds from the independently captured monitoring window.
A threshold failure exits non-zero and cannot emit a passing manifest.

## 4. Degraded dependency drills

`run-failure-scenario.ps1` is staging-only and requires the explicit
`-AcknowledgeServiceDisruption` switch. Run `scanner`, `database`, and `backup`
one at a time. The script captures baseline/recovery health, injects only the
named Compose service failure, verifies the expected API or backup failure, and
restores the service in `finally`.

```powershell
$env:ACCEPTANCE_API_TOKEN = '<staging-api-token>'
./scripts/acceptance/run-failure-scenario.ps1 `
  -ConfigFile ./managed-staging.env -EnvFile ./.env.production `
  -Scenario scanner -AcknowledgeServiceDisruption
```

R2 failure must be injected by an approved staging egress control that blocks
only the configured R2 endpoint; do not revoke a shared key or disconnect the
application network. Record: upload returns `503 file_scan_failed` for scanner
outage or a non-success storage error for R2 outage, quarantine prefix returns
to baseline, the alert reaches the named on-call, and the recovered upload
succeeds. Because that egress control is installation-specific, R2 outage stays
`UNVERIFIED` until its external evidence is attached.

For the database drill, authenticated API traffic must fail while PostgreSQL is
stopped and recover after it is healthy. For the backup drill, an intentionally
missing backup credential must produce a non-zero one-shot backup and a failed
backup status/alert; migration must not be started.

## 5. Encrypted object backup and combined restore

Create the database archive with `scripts/prod/backup.ps1`. Then create a
separate encrypted object archive. For R2:

```powershell
$env:AWS_ACCESS_KEY_ID = '<source-read-key>'
$env:AWS_SECRET_ACCESS_KEY = '<source-read-secret>'
$env:RECOVERY_AWS_ACCESS_KEY_ID = '<recovery-write-key>'
$env:RECOVERY_AWS_SECRET_ACCESS_KEY = '<recovery-write-secret>'
./scripts/prod/backup-objects.ps1 `
  -Provider s3 -AgeRecipient 'age1...' `
  -S3Endpoint 'https://ACCOUNT.r2.cloudflarestorage.com' `
  -S3Bucket '<application-bucket>' `
  -RecoveryS3Endpoint 'https://ACCOUNT.r2.cloudflarestorage.com' `
  -RecoveryS3Bucket '<different-recovery-bucket>'
```

The object archive stores each physical object under an index-based safe name
and keeps original storage keys only inside the encrypted manifest. Every object
has an exact size and SHA-256. Any download, encryption, upload, or checksum
failure removes partial output.

Restore to a project name beginning with `smartupcms-restore-`; other names are
rejected. For an R2 drill, the target prefix must exactly equal
`restore-drill/<isolated-project>/` and use separate restore credentials:

```powershell
$env:RESTORE_AWS_ACCESS_KEY_ID = '<drill-bucket-key>'
$env:RESTORE_AWS_SECRET_ACCESS_KEY = '<drill-bucket-secret>'
./scripts/prod/restore-combined.ps1 `
  -DatabaseBackupFile D:/secure-backup/smartupcms-....dump.age `
  -ObjectBackupFile D:/secure-backup/smartupcms-objects-....tar.age `
  -AgeIdentityFile D:/offline/backup-age-identity.txt `
  -IsolatedProjectName smartupcms-restore-v1-2-3 `
  -TargetObjectProvider s3 `
  -TargetS3Endpoint 'https://ACCOUNT.r2.cloudflarestorage.com' `
  -TargetS3Bucket '<isolated-drill-bucket>' `
  -TargetS3Prefix 'restore-drill/smartupcms-restore-v1-2-3/' `
  -MaxRpoSeconds '<approved-RPO-seconds>' `
  -MaxRtoSeconds '<approved-RTO-seconds>'
```

Before creating the isolated PostgreSQL container, the command verifies both
encrypted checksums, decrypts both artifacts, validates `pg_restore --list`,
rejects unsafe tar paths, and verifies every object size/hash. It then compares
distinct `mf_files` physical keys with the object inventory, rejects missing or
orphan objects, verifies sampled downloads, and records row counts, RPO, and
RTO. The isolated project and exact drill prefix are removed unless
`-KeepIsolatedTarget` is set.

Run three negative drills and retain their non-zero output: missing object
archive, wrong age identity, and an object archive truncated after copying.
None may start or modify the production Compose project.

## 6. Release integrity and final decision

Download every asset from the immutable GitHub Release to one directory. With
authenticated `gh` plus `cosign` installed:

```powershell
./scripts/release/verify-published-release.ps1 `
  -ReleaseDirectory D:/release-assets/vX.Y.Z `
  -Repository qahhor/dwh -Version vX.Y.Z
```

This independently verifies `SHA256SUMS`, five digest-addressed images, Cosign
OIDC signatures, GitHub provenance attestations, and SPDX/CycloneDX documents.
Deploy only the verified digests. Finish the production launch checklist with
links to preflight, capacity, failure, alert, release-integrity, rollback, and
combined-restore evidence. Missing evidence is `UNVERIFIED` and the final record
must remain **NO-GO**.
