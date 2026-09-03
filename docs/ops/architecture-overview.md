# SmartupCMS operations architecture

**Version:** 2.0

**Updated:** 2026-09-02

**Scope:** the supported single-organization Docker Compose topology.

## Runtime map

```text
Internet
   |
   | HTTPS :443 (operator-managed Cloudflare or reverse proxy)
   v
web :8080  -------------------->  server :8080
   |                                 |       |
   | frontend network                |       +--> configured mail/SMS/messenger providers
   |                                 |
   |                                 +--> PostgreSQL :5432
   |                                 +--> Typesense :8108
   |                                 +--> ClamAV :3310
   |                                      backend network (internal)
   |
   +-- one browser origin for SPA and /api

backup --> PostgreSQL (read-only role) --> age-encrypted .dump.age
                                      \--> local volume or explicit S3/R2 target
```

The production Compose file is
[`deploy/compose/docker-compose.prod.yml`](../../deploy/compose/docker-compose.prod.yml).
Only `web` publishes a host port, bound to `127.0.0.1:8080` by default. A host
proxy or Cloudflare terminates TLS and forwards to that listener. `server`,
PostgreSQL, Typesense, ClamAV, and the management port remain on Compose
networks.

## Components and trust boundaries

| Component | Trust boundary | Persistent state |
|---|---|---|
| `web` | Untrusted browser input; reverse proxy to application API | none |
| `server` | Authentication, server-side authorization, validation, business transactions | `server-data` for local file storage |
| `postgres` | Authoritative transactional state and audit records | `postgres-data` |
| `typesense` | Derived search index; not an authorization source | `typesense-data` |
| `clamav` | Untrusted quarantined upload bytes; malware verdicts fail closed | `clamav-data` for signatures |
| `backup` | Dedicated read-only database role; encryption before persistence | `backups`, `backup-status` |
| `migrate` | One-shot schema mutation with application database credentials | PostgreSQL schema history |

The browser receives a `DWH_SESSION` HttpOnly session cookie. Mutating browser
requests are protected by CSRF controls, and permissions are checked by the
server. Search results must remain constrained by application authorization;
Typesense is not exposed to browsers in production.

## Data flows

- Business writes: browser -> `web` -> `server` -> PostgreSQL transaction and
  audit record.
- File uploads: `server` validates metadata and content, stores bytes under an
  unpublished quarantine key, and sends them to ClamAV before publishing in the
  configured `local_disk` or S3-compatible provider. Database backups do not
  contain these object bytes.
- Search: `server` writes and queries Typesense through the private network.
- Notifications and webhooks: outbound requests occur only when an
  administrator selects and configures a real provider. Console providers are
  the default.
- Announcements: authorship, publication, dismissal, and audit remain inside the
  installation.
- Backups: `pg_dump` streams directly through `age`; plaintext dumps are not
  written to disk. A SHA-256 sidecar and sanitized status file accompany the
  encrypted archive.

No telemetry, enrollment, licensing callback, or other mandatory external
connection is part of the runtime.

## Storage choices

`DWH_PROVIDER_STORAGE=local_disk` stores uploaded objects in `server-data`. This
survives container recreation but not loss of the Docker volume or host.
Production operators using local disk must implement an independent encrypted
copy and restore drill for that volume.

`DWH_PROVIDER_STORAGE=s3` supports S3-compatible endpoints. Smartup-managed
installations use Cloudflare R2; self-hosters may select another compatible
provider. Bucket encryption, versioning, lifecycle, retention, and recovery are
operator/provider responsibilities and must be tested before launch.

## Deployment invariants

1. Images use immutable release tags; `latest` is not a release input.
2. Migrations run as a separate one-shot service before the new server starts.
3. An upgrade with existing PostgreSQL data must create an encrypted backup
   first; backup failure stops deployment before migration.
4. The backup identity is held outside the deployment host or backup volume.
5. The application reads only a sanitized backup status file, never backup
   credentials or decrypted content.
6. Destructive schema rollback is not automated. Recovery uses the documented
   image rollback or encrypted restore procedure.

## Known operational limits

- The bundled database backup is scheduled `pg_dump`, not continuous WAL
  archiving; default RPO is up to 24 hours.
- Object bytes require a separate local-volume or bucket recovery policy.
- Metrics collection, alert delivery, and external log aggregation are not
  provisioned by the Compose file and must be supplied by the operator.
- High availability across hosts is not provided by the bundled single-host
  topology.

These limits must be reflected in any managed SLA and in the
[production launch checklist](production-launch-checklist.md).
