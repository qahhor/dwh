# SmartupCMS operations runbook

**Version:** 2.0

**Updated:** 2026-09-05

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
   Readiness (`/actuator/health/readiness` on the management port) is DOWN
   within the health timeout (`DWH_SYSTEM_HEALTH_TIMEOUT`, 2 s by default)
   when the main database stops answering; liveness ignores it, so the
   container is taken out of traffic, not restarted. pg-dwh, Typesense (if
   enabled) and ClamAV (if scanning is required) appear in
   `/actuator/health` but do not affect readiness: search falls back to
   PostgreSQL, uploads fail closed and the DWH module degrades alone.
3. PostgreSQL health, disk capacity, and filesystem errors.
4. Whether a migration failed or the release tag changed unexpectedly.

Logs are kept in two places (decision of 2026-09-27):

- the server writes `/var/lib/smartupcms/logs/server.log` on its data volume
  and archives it to `server.log.<year>-W<week>.<n>.gz` every week or at
  100 MB, whichever comes first; 12 weeks and 2 GB of archives at most
  (`SMC_LOG_*` in the environment file);
- every container's console log rotates at 100 MB, five compressed files
  (Docker's json-file driver rotates by size only).

### Audit log archive

The audit log is kept in daily partitions and archived by the server every
night at 03:45 UTC (decision of 2026-09-27; `SMC_AUDIT_ARCHIVE_*` in the
environment file):

- days closed for a full day (a transaction started before midnight may still
  commit into the day) that no archive holds yet go into one file
  `audit-log_<from>_<to>_<stamp>_<id>.jsonl.gz` once a week, or earlier when
  they reach 100 MB; one line is one row of `audit_log`;
- the target is a directory on the server (`SMC_AUDIT_ARCHIVE_TARGET=local`,
  `/var/lib/smartupcms/audit-archive` on the data volume) or a bucket of its
  own (`s3`, with `SMC_AUDIT_ARCHIVE_S3_*`);
- the file is read back and matched by SHA-256 and row count before it
  counts; an archive that does not match is removed and retried the next night;
- files older than 90 days are removed (`SMC_AUDIT_ARCHIVE_RETENTION`);
- with `SMC_AUDIT_ARCHIVE_DELETE_AFTER_ARCHIVE=true` the archived days leave
  the database, each only if its file is still in the store; the database
  itself refuses a day that no verified, unexpired archive holds, and a day
  whose row count differs from its archive. A day whose archive expired is
  archived again before it leaves. Off by default: the archive is then a copy,
  made once per day.
- one instance runs the archive at a time (a lease in the database); a
  verified archive record is permanent, so every day that left the database
  keeps its trace. With deletion on, the application role can still remove
  closed audit days: that is what the switch allows.

Every archive, removal and expiry is a security event (`AUDIT_ARCHIVED`,
`AUDIT_PARTITIONS_DROPPED`, `AUDIT_ARCHIVES_EXPIRED`). The files hold
personal data of the audit log: keep the directory or bucket as private as the
database backups.

To read an archive back into a working table (through an operator-controlled
PostgreSQL session):

```sql
create table audit_restore (like audit_log);
create temporary table audit_restore_lines (line text);
-- psql: \copy audit_restore_lines (line) from program 'gzip -dc audit-log_....jsonl.gz' with (format csv, quote e'\x01', delimiter e'\x02')
insert into audit_restore
select (jsonb_populate_record(null::audit_restore, line::jsonb)).* from audit_restore_lines;
```

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
dump, encryption, checksum, or upload fails — for the DWH database as well as
the CMS one; `backup-bootstrap` grants the backup role read access to both. Do not run a migration until a
one-shot backup succeeds or a documented risk owner explicitly stops the
release.

Backup recovery is not proven until [an isolated restore drill](maintenance-guide.md#restore-drill)
passes. Database success does not prove recovery of uploaded objects.
Use `backup-objects.ps1` and `restore-combined.ps1` for the release drill; the
combined evidence must reject a missing/wrong age key, partial object archive,
inventory mismatch, or RPO/RTO breach.

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

Webhooks are fail-closed unless `DWH_WEBHOOKS_ENABLED=true` and every destination
host is present in `DWH_WEBHOOKS_ALLOWED_HOSTS`. Do not add wildcard hosts. Keep
`DWH_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES=false` on Smartup-managed or
internet-facing installations. A client-owned private target may opt in only
after the destination and network boundary are reviewed. The API returns a
signing secret only when the subscription is created; rotate by replacing the
subscription if that one-time value is lost or exposed.

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

## Database roles and least privilege

SmartupCMS enforces strict role separation across database operations:
- **`smartupcms_migrator`**: Schema owner with DDL privileges. Used only by Flyway during bootstrap and release upgrades.
- **`smartupcms`**: Application runtime user. Has DML privileges (`SELECT`, `INSERT`, `UPDATE`, `DELETE`) on public tables; lacks `CREATE TABLE`, `DROP`, `ALTER`, `TRUNCATE`, and superuser privileges.
- **`smartupcms_backup`**: Read-only user (`SELECT` on public tables + `pg_read_all_data`) used by `backup-loop.sh`.

If an application feature reports `permission denied for table ...` or fails with DDL errors at runtime, confirm the runtime is connecting as `smartupcms` and that Flyway was successfully run by `smartupcms_migrator`.

## Audit partition maintenance

Audit logs are partitioned monthly into `audit_log_YYYY_MM` tables:
- An automated worker maintains a forward partition runway of at least 3 months.
- Under least privilege, the runtime user invokes restricted `SECURITY DEFINER` functions:
  ```sql
  -- Manually create a future partition:
  SELECT audit_log_create_partition(2026, 11);

  -- Manually detach an aged partition for archival:
  SELECT audit_log_detach_partition(2025, 9);
  ```
- If partition creation fails, inspect postgres logs to verify function permissions and ensure disk space is sufficient.

## Search outbox sync and reconciliation

Mutations to tasks, files, and notes are staged transactionally in `search_outbox` and asynchronously delivered to Typesense by `SearchDeliveryWorker`:
- Transient Typesense outages do not abort client transactions. Events retry with exponential backoff and jitter up to 8 attempts.
- If search results become stale, check the outbox queue status and trigger reconciliation via the management API:
  ```bash
  curl -X POST http://127.0.0.1:8080/api/v1/search/management/reconcile \
    -H "Authorization: Bearer dwh_operator_token"
  ```
- Alternatively, restarting the server container triggers non-blocking startup reconciliation automatically.

## Task optimistic concurrency conflicts (HTTP 409)

Tasks enforce monotonic compare-and-set versioning via the `revision` column:
- If two users or processes modify the same task concurrently with a stale `expectedRevision`, the API returns HTTP 409 with error code `task_revision_conflict`.
- Triage: The UI prompts the user to refresh the task to view the latest changes before reapplying edits. If automated integrations receive 409, they should re-fetch the current task revision and retry.

## Rate limiting and upload concurrency (HTTP 429)

- **API and Auth Rate Limiting**: Client IPs are resolved via trusted proxy validation (`ClientIpResolver`). Floods from an untrusted origin or brute-force attempts are rejected with HTTP 429 (`RATE_LIMITED`).
- **File Upload Admission**: File uploads acquire a permit from an in-memory semaphore (default 10 permits). If the server reaches capacity under burst load, excess uploads return HTTP 429 immediately rather than exhausting server heap.
- Triage: Inspect NGINX logs for authentic client IPs and evaluate whether `dwh.security.rate-limit.login.capacity` or `dwh.files.max-concurrent-uploads` require adjustment for enterprise scale.

## Export streaming and memory bounding

- Task exports (`/api/v1/reports/tasks-export-csv` and XML) stream directly to the client with `defaultRowFetchSize: 500` and a hard cap (`dwh.reports.export.max-rows`, default 50,000 rows).
- If a client disconnects mid-download, `ReportService` catches `ClientAbortException | IOException`, cleanly aborts the database cursor, and returns the Hikari connection to the pool without leaking resources.

## Background jobs and database timeouts

Uploads are parsed and applied, exports written and maintenance run by the job
queue (`fnd_job_queue`, plan 10/10 item 3.8). Every server instance runs it:

- a job is leased in a short transaction and runs outside it; the instance
  renews the lease while the job works. A job whose instance died is taken by
  another one once its lease runs out (`DWH_JOBS_LEASE`, 5 minutes by default);
- a failed job is retried after 30 seconds, then 1, 2, 4 minutes and so on up
  to one hour (`DWH_JOBS_RETRY_BACKOFF`, `DWH_JOBS_RETRY_BACKOFF_MAX`); after 5
  attempts (`DWH_JOBS_MAX_ATTEMPTS`) it is marked failed. Each attempt is a row
  in `fnd_job_runs` with its error;
- scheduled jobs are enqueued under an advisory lock, so several instances
  enqueue each due job once;
- `jobs_enabled=false` in the global settings (`md_settings`, `user_id is null`)
  stops the queue on every instance.

Failed jobs stay in the queue for inspection:

```sql
select q.id, q.handler, q.attempts, q.failed_at, r.error
  from fnd_job_queue q
  join lateral (select error from fnd_job_runs where queue_id = q.id order by id desc limit 1) r on true
 where q.failed_at is not null;
-- once the cause is fixed, run one again:
update fnd_job_queue set failed_at = null, attempts = 0, next_run_at = now() where id = <id>;
```

The main database pool ends a statement that runs longer than
`SMC_DB_STATEMENT_TIMEOUT` and a session idle inside a transaction longer than
`SMC_DB_IDLE_IN_TRANSACTION_TIMEOUT` (both 60 seconds by default; PostgreSQL
durations such as `90s` or `5min`, `0` turns a limit off). Jobs hold no
transaction while they work, so they need no longer limit. The `migrate`
profile turns both off: every migration file sets its own. pg-dwh has its own
limits (`APP_DWH_STATEMENT_TIMEOUT`, `APP_DWH_MAINTENANCE_STATEMENT_TIMEOUT`).
A request that fails with SQL state `57014` (statement timeout) or a closed
connection after `idle-in-transaction timeout` points at a query or code path
to fix, not at a limit to raise.

## Retention of journal tables

Plan 10/10, item 3.13 (ADR-0025). Every night (`SMC_RETENTION_CRON`, default 03:30) the retention job deletes
the rows of ten journal tables past their retention, in batches of `SMC_RETENTION_BATCH_SIZE` rows (5000), at
most `SMC_RETENTION_MAX_BATCHES` batches (200) per table and run; a larger backlog continues the next night.
Each batch is its own short transaction; two nodes may run the job at once.

| Setting (days) | Table | Default | Rows kept however old |
|---|---|---|---|
| `SMC_RETENTION_SECURITY_EVENTS_DAYS` | `security_events` | 365 | — |
| `SMC_RETENTION_LOGIN_ATTEMPTS_DAYS` | `kauth_login_attempts` | 30 | — |
| `SMC_RETENTION_OTP_CODES_DAYS` | `kauth_otp_codes` (after expiry) | 7 | — |
| `SMC_RETENTION_PASSWORD_RESET_CODES_DAYS` | `kauth_password_reset_codes` (after expiry) | 7 | — |
| `SMC_RETENTION_CLOSED_SESSIONS_DAYS` | `kauth_sessions` (after closing) | 90 | open sessions |
| `SMC_RETENTION_WEBHOOK_LOGS_DAYS` | `kwh_logs` | 90 | — |
| `SMC_RETENTION_WEBHOOK_OUTBOX_DAYS` | `kwh_outbox` (sent, dead letter) | 30 | pending, in progress |
| `SMC_RETENTION_INBOX_DAYS` | `ms_notifications` | 180 | — |
| `SMC_RETENTION_NOTIFICATION_OUTBOX_DAYS` | `ms_notification_outbox` (sent, dead letter) | 30 | pending, in progress |
| `SMC_RETENTION_JOB_RUNS_DAYS` | `fnd_job_runs` (finished) | 90 | running jobs |

`0` keeps a table's rows forever. The audit log is not here: its partitions leave the database only through
the verified archive ("Audit log archive"). The log line `retention_purged policy=… rows=…` and the metric
`dwh_retention_deleted_rows_total{policy}` show each run; `retention_failed` names a table the run could not
clean (the others are cleaned regardless, and the next run retries it).

## Cache across nodes

Plan 10/10, item 3.13 (ADR-0025). Each node caches reference data (task statuses and types, modules, menu,
custom fields) for up to ten minutes. A change clears the cache on its node and, once committed, sends
`NOTIFY smc_cache`; every other node clears the same cache within a second or two. Each node holds one
connection of its pool (of 20) to listen. If that connection drops, the node logs
`cache_invalidation_listen_failed`, clears all its caches and listens again with a growing pause (up to 30 s);
until then another node's change reaches it at the latest when its entries expire.

## Incident closure

Document impact, timeline, root cause, data/security assessment, remediation,
test evidence, and follow-up owner/date. Update this runbook when the actual
recovery path differed from the documented one.
