# SmartupCMS maintenance guide

**Version:** 2.0

**Updated:** 2026-09-02

## Release upgrade

1. Read the release notes and migration notes.
2. Verify the target image signatures and release manifest.
3. Confirm current health, free space, and a successful recent restore drill.
4. Set the immutable target `APP_VERSION` in `.env.production`.
5. Run the supported deploy script:

```bash
bash scripts/prod/deploy.sh
```

```powershell
./scripts/prod/deploy.ps1
```

The script creates an encrypted database backup before migrating an existing
database and stops on backup, pull, migration, or readiness failure. Do not run
Flyway manually around a failed gate. Follow [rollback](rollback.md).

## On-demand encrypted backup

```bash
bash scripts/prod/backup.sh
```

```powershell
./scripts/prod/backup.ps1
```

Success means all of the following happened: `pg_dump` completed, the stream was
encrypted with the configured age recipient before persistence, a SHA-256 file
was written, the configured local/S3 target accepted the artifact, and sanitized
status was updated. A zero-byte file, log message, or stale status is not backup
evidence.

The System page compares the successful backup timestamp with
`DWH_BACKUP_MAX_AGE`. `CURRENT` means the measured age is within that configured
threshold; `STALE` means it has been exceeded. `NOT_CONFIGURED` is an explicit
policy gap, not a healthy result. The threshold does not replace a restore drill
or prove that uploaded objects are recoverable.

The database backup does not include uploaded objects. Operate and test the
independent `server-data` or S3 bucket recovery policy.

## Restore drill

Test restore at least monthly and before a risky upgrade. Use a separate
`PROJECT_NAME`, host port, database volume, and object-storage test location.
Never point a drill at production.

Prepare a temporary environment file based on the production template, with a
unique project name such as `smartupcms-restore-test` and `HTTP_PORT=18080`.
Start only its database, then restore the encrypted archive:

```bash
docker compose -f deploy/compose/docker-compose.prod.yml \
  --env-file .env.restore-test up -d --wait postgres
COMPOSE_FILE=deploy/compose/docker-compose.prod.yml \
ENV_FILE=.env.restore-test \
bash scripts/prod/restore.sh /secure/backup.dump.age /secure/backup-age-identity.txt
```

PowerShell equivalent:

```powershell
docker compose -f deploy/compose/docker-compose.prod.yml `
  --env-file .env.restore-test up -d --wait postgres
./scripts/prod/restore.ps1 `
  -EnvFile .env.restore-test `
  -BackupFile C:\secure\backup.dump.age `
  -AgeIdentityFile C:\secure\backup-age-identity.txt
```

The restore script verifies SHA-256 and the `pg_restore` catalog before stopping
the server. It preserves the previous database under a timestamped name, streams
decrypted data directly into PostgreSQL, applies forward migrations, and waits
for readiness.

Validate sign-in, representative record counts, permissions, audit history,
search reindex behavior, and uploaded-object recovery. Record archive timestamp,
checksum, recovery duration, and verifier. Remove only the explicitly named
disposable project after the evidence is retained:

```bash
docker compose -f deploy/compose/docker-compose.prod.yml \
  --env-file .env.restore-test down --volumes --remove-orphans
```

## Secret rotation

| Secret | Rotate when | Minimum verification |
|---|---|---|
| Initial/admin credentials | bootstrap complete, suspected exposure, policy date | old credential denied; administrator sign-in works |
| Application database password | suspected exposure or policy date | migrations and server readiness pass |
| Backup database password | suspected exposure or policy date | bootstrap role and one-shot backup pass |
| S3/R2 keys | suspected exposure or provider policy date | upload/download/delete plus encrypted backup pass |
| Mail/SMS/messenger token | suspected exposure or provider policy date | test delivery and redacted logs |
| age recipient | planned rekey or identity exposure | new backup and isolated restore pass before retiring old identity |

Store secrets outside Git, use least-privilege provider credentials, and retain
an old age identity until every archive encrypted to it expires or is rekeyed.

## Database and storage maintenance

- Daily: check health, backup age/status, delivery dead letters, and disk usage.
- Weekly: review error trends, object-store failures, PostgreSQL volume growth,
  and Typesense health.
- Monthly: restore drill, base-image/dependency review, audit partition horizon,
  and certificate expiry.
- Quarterly: access review, rollback exercise, object recovery drill, capacity
  forecast, and retention review.

Use PostgreSQL maintenance commands only after measuring the need. Do not edit
Flyway history or rewrite an applied migration. Audit retention, personal-data
retention, and object lifecycle must match the organization's documented policy;
no universal retention period is asserted by this repository.
