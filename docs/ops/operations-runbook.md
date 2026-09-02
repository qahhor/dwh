# SmartupCMS operations runbook

**Version:** 2.0

**Updated:** 2026-09-02

**Audience:** the operator responsible for one SmartupCMS installation.

Use the exact production Compose and environment files for every command:

```bash
docker compose -f deploy/compose/docker-compose.prod.yml \
  --env-file .env.production ps
```

## Severity and first response

| Severity | Examples | First response target |
|---|---|---|
| P1 | unavailable installation, active compromise, confirmed data loss/corruption | immediate |
| P2 | failed migration/backup, widespread sign-in failure, sustained delivery failure | same working day |
| P3 | degraded performance, isolated provider failure, capacity warning | planned with owner |

For P1/P2: record UTC time, release tag, affected workflows, service state, and
sanitized logs; stop further rollout; protect evidence; identify an incident
owner. Never paste credentials, session cookies, personal data, object contents,
or database dumps into a public issue.

## Daily checks

```bash
docker compose -f deploy/compose/docker-compose.prod.yml \
  --env-file .env.production ps
curl --fail --silent --show-error http://127.0.0.1:8080/healthz
```

Confirm all long-running services are healthy, the backup status in the System
screen is successful and younger than the accepted RPO, disk usage is below the
operator threshold, TLS is valid, and error/dead-letter alerts are quiet.

## Service is unavailable

Capture evidence before restarting:

```bash
docker compose -f deploy/compose/docker-compose.prod.yml \
  --env-file .env.production logs --since 30m server web postgres typesense
```

Check in order:

1. `web` health and host reverse-proxy/TLS routing.
2. `server` readiness and its PostgreSQL/Typesense connection errors.
3. PostgreSQL health, disk capacity, and filesystem errors.
4. Whether a migration failed or the release tag changed unexpectedly.

Restart only the failed stateless service when the cause is understood:

```bash
docker compose -f deploy/compose/docker-compose.prod.yml \
  --env-file .env.production up -d --wait web server
```

Do not delete volumes, edit Flyway history, or repeatedly restart a corrupt
database. Use [rollback](rollback.md) when a release caused the incident.

## Sign-in or authorization failure

- Confirm whether the failure affects one user, one role, or every user.
- Check browser time, TLS, cookie/CSRF errors, account state, and recent role
  changes.
- Reproduce the denied API request with a test account of the same role; do not
  weaken server-side permission checks to restore access.
- Review audit entries for account, role, token, and session changes.
- If all administrators are locked out, preserve evidence and use a documented,
  reviewed recovery procedure. No emergency default password is provided.

An action visible in the UI but rejected by the API is a UI permissions defect;
an action accepted by the API without permission is a P1 security incident.

## Backup failure or stale status

```bash
docker compose -f deploy/compose/docker-compose.prod.yml \
  --env-file .env.production logs --since 24h backup
bash scripts/prod/backup.sh
```

Check the age recipient, read-only database credential, target capacity, and
S3/R2 endpoint/permissions. The backup process deliberately exits non-zero if
dump, encryption, checksum, or upload fails. Do not run a migration until a
one-shot backup succeeds or a documented risk owner explicitly stops the
release.

Backup recovery is not proven until [an isolated restore drill](maintenance-guide.md#restore-drill)
passes. Database success does not prove recovery of uploaded objects.

## Search failure

If PostgreSQL-backed workflows work but search fails, inspect Typesense and
server logs. Keep Typesense private and never point the browser directly at it.
Because the search index is derived data, prefer a documented reindex over
restoring it as authoritative state. Confirm authorization filtering after any
reindex.

## Notification or webhook failure

Check whether a real provider was explicitly selected; console providers do not
deliver externally. Inspect sanitized server logs and dead-letter state, then
test provider DNS/TLS, credential scope, quota, and destination policy. Avoid
retry storms: fix the cause before replaying failed deliveries.

## Storage failure

For local storage, check `server-data` capacity, permissions, and host health.
For S3-compatible storage, check endpoint reachability, bucket policy,
credentials, region/path-style settings, quota, and provider incident status.
Do not switch providers during an incident without an inventory and migration
plan; database metadata and object bytes must stay consistent.

## Suspected compromise

1. Restrict external access without destroying containers or volumes.
2. Preserve UTC logs, image digests, Compose configuration, audit records, and
   provider access logs in a protected location.
3. Revoke affected sessions, API/provider credentials, and edge tokens.
4. Report privately according to [SECURITY.md](../../SECURITY.md).
5. Recover from verified images and backups; validate permissions and object
   integrity before reopening access.

## Incident closure

Document impact, timeline, root cause, data/security assessment, remediation,
test evidence, and follow-up owner/date. Update this runbook when the actual
recovery path differed from the documented one.
