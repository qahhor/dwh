# SmartupCMS production deployment

**Version:** 2.0

**Updated:** 2026-09-05

**Supported path:** one organization on one Docker Compose host.

Deployment is not production-ready until every blocking item in the
[production launch checklist](production-launch-checklist.md) is evidenced.
Smartup-managed Hetzner + Cloudflare + R2 installations must also pass the
[managed infrastructure acceptance](managed-infrastructure-acceptance.md).

## 1. Prerequisites

- Linux host with Docker Engine 26+ and Docker Compose v2.
- A dedicated non-root deployment account and restricted deployment directory.
- DNS, TLS, and a host reverse proxy or Cloudflare forwarding HTTPS to
  `127.0.0.1:8080`.
- Capacity monitoring for the Docker data directory and backup target.
- `age-keygen` on a trusted administrator workstation.
- An immutable SmartupCMS release tag and its verified release artifacts.
- A documented recovery location for uploaded objects as well as the database.

The production file publishes only the web origin. Do not publish PostgreSQL,
Typesense, port 9090, or the Docker socket.

## 2. Prepare configuration and secrets

From a clean release checkout:

```bash
cp deploy/compose/.env.example .env.production
install -d -m 700 deploy/compose/.secrets
chmod 600 .env.production
```

Set `APP_VERSION` to an immutable SemVer tag and replace every blank or example
credential in `.env.production`. Create these files with mode `0600`:

```text
deploy/compose/.secrets/database-password
deploy/compose/.secrets/backup-database-password
```

The first file must contain the same value as `DB_PASSWORD`. The second must be
a distinct random password for the dedicated read-only backup role. Do not add a
trailing explanatory line or commit the files.

For Smartup-managed installations, also copy the five full `SERVER_IMAGE`,
`WEB_IMAGE`, `BACKUP_IMAGE`, `POSTGRES_IMAGE`, and `TYPESENSE_IMAGE` references
from the signed release bundle. Each must end in `@sha256:<digest>`; the managed
host check rejects tag-only containers.

Generate the age identity on a trusted workstation, store the private identity
outside the deployment host, and place only its public recipient in
`BACKUP_AGE_RECIPIENT`:

```bash
age-keygen -o backup-age-identity.txt
age-keygen -y backup-age-identity.txt
```

For local backup storage, keep `BACKUP_STORAGE_MODE=local` and put the Docker
backup volume on storage that survives loss of the application volume. For an
off-host S3/R2 copy, set `BACKUP_STORAGE_MODE=s3`, configure endpoint, bucket,
region, and prefix, then create these mode-`0600` files:

```text
deploy/compose/.secrets/backup-s3-access-key-id
deploy/compose/.secrets/backup-s3-secret-access-key
```

For application file storage, `DWH_PROVIDER_STORAGE=local_disk` requires an
operator backup for `server-data`. With `DWH_PROVIDER_STORAGE=s3`, configure the
S3-compatible application variables and verify upload, download, delete, and
recovery against the selected provider. Smartup-managed deployments use
Cloudflare R2.

Set `DWH_BACKUP_MAX_AGE` to the installation's approved maximum recovery-point
age using a Spring duration such as `26h`. The default `0s` deliberately means
"not configured": the System page reports the policy gap and never treats a
successful backup as current. This threshold is independent of
`BACKUP_INTERVAL_SECONDS`; choose both values so normal scheduling completes
before the approved maximum age.

Every upload is checked against executable signatures and strict MIME magic
bytes before storage. Production Compose starts the official multi-architecture
ClamAV image pinned by version and digest, persists signatures in `clamav-data`,
and waits for its built-in health check before starting the server. Both
`DWH_FILE_SCANNER_REQUIRED` and the ClamAV provider are forced on in this
supported topology. The temporary object remains under an unpublished
quarantine key until every active scanner returns `CLEAN`; an infected verdict
or scanner failure deletes it. Operators may override `CLAMAV_IMAGE` only with
another reviewed immutable image and must monitor signature freshness and
scanner memory on the host.

## 3. Validate before first start

Outbound webhooks are disabled by default. If the installation needs them, set
`DWH_WEBHOOKS_ENABLED=true` and list each exact destination host, without scheme,
path, or wildcard, in comma-separated `DWH_WEBHOOKS_ALLOWED_HOSTS`. Keep
`DWH_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES=false` for Smartup-managed and public
installations. Client-owned internal destinations require a documented network
review before opting in. Host OS/firewall egress policy remains the final
network boundary.

```bash
docker compose -f deploy/compose/docker-compose.prod.yml \
  --env-file .env.production config --quiet
pwsh -NoProfile -File scripts/prod/test-release-config.ps1
pwsh -NoProfile -File scripts/prod/test-backup-status.ps1
pwsh -NoProfile -File scripts/acceptance/test-managed-acceptance.ps1
```

Inspect the rendered configuration without sharing its environment values.
Confirm that only `web` contains `ports:` and that the bind address is loopback.

## 4. Deploy

Linux/macOS:

```bash
bash scripts/prod/deploy.sh
```

PowerShell:

```powershell
./scripts/prod/deploy.ps1
```

The script validates Compose, pulls release images, creates a mandatory
pre-migration encrypted backup when an existing database is present, starts
dependencies, runs Flyway, refreshes the read-only backup role, and waits for
all services. Any failed step exits non-zero. A failed backup prevents the
migration.

## 5. Configure TLS

Forward the public HTTPS origin to `http://127.0.0.1:8080`. Preserve the `Host`
and forwarding headers, enable WebSocket/SSE-friendly response streaming, and
set upload limits/timeouts to the organization's file policy. At the edge,
enable rate limiting and managed WAF rules appropriate to the deployment, but do
not bypass application authentication or authorization.

From an external network, verify that only HTTPS is reachable. Requests to
PostgreSQL, Typesense, the server port, and `/actuator/*` must fail.

## 6. Acceptance checks

```bash
docker compose -f deploy/compose/docker-compose.prod.yml \
  --env-file .env.production ps
curl --fail --silent --show-error http://127.0.0.1:8080/healthz
```

Then verify through HTTPS:

1. Sign in as the initial administrator and immediately change the password.
2. Create a non-administrator role and user; confirm a denied action is denied
   by the API as well as hidden in the UI.
3. Upload and download a representative file.
4. Create and publish a local announcement, then verify its audit entry.
5. Run an encrypted one-shot backup and perform an isolated restore drill.
6. Configure alerts for service health, disk capacity, backup age/failure,
   certificate expiry, and elevated error rate.

For a Smartup-managed installation, run the external preflight, capacity and
failure profiles, independent published-release verification, and combined
database/object restore exactly as documented in the managed acceptance
runbook. Local Compose or MinIO emulation cannot replace target evidence.

Remove `ADMIN_PASSWORD` from `.env.production` after bootstrap if the current
installation no longer requires it, then redeploy and confirm restart succeeds.

## 7. Clean installation and removal

A new `PROJECT_NAME` creates isolated volumes and is the supported clean-install
test. `docker compose down` preserves data. `docker compose down --volumes`
permanently deletes the installation's database, files, search index, backups,
and backup status; use it only for an explicitly identified disposable project
after confirming the project name and recovery requirements.

For upgrades use the [maintenance guide](maintenance-guide.md). For incidents
use the [operations runbook](operations-runbook.md) and
[rollback procedure](rollback.md).
