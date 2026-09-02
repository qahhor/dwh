# SmartupCMS production launch checklist

**Version:** 2.0

**Updated:** 2026-09-02

Every unchecked blocking item means **NO-GO** for that installation. Evidence
must identify the release tag, environment, UTC time, command/check, result, and
owner. A commercial SLA cannot override a failed safety gate.

## Release integrity

- [ ] Release tag is immutable SemVer and resolves to the reviewed commit.
- [ ] Required CI and DCO checks are green on that commit.
- [ ] Server, web, and backup image digests match the release manifest.
- [ ] Image signatures, provenance, and SBOMs verify successfully.
- [ ] No Critical/High accepted vulnerability lacks a documented owner,
      mitigation, expiry date, and release decision.
- [ ] Changelog, migration notes, known limits, and rollback path are published.

## Installation and network

- [ ] Production Compose renders successfully with no default or blank required
      credential and no `latest` tag.
- [ ] Only the web origin is published; PostgreSQL, Typesense, server, management
      endpoints, secret files, and Docker socket are unreachable externally.
- [ ] HTTPS, certificate renewal, security headers, upload limits, and edge rate
      limits are tested from an external network.
- [ ] `.env.production` and `.secrets` have restricted ownership/permissions and
      are excluded from source control and support artifacts.
- [ ] Host capacity covers forecast data, 50 GB/month object growth assumption,
      database growth, logs, backup retention, and restore workspace with alert
      thresholds below exhaustion.

## Data and recovery

- [ ] Flyway migration succeeds from the oldest supported release and from an
      empty database.
- [ ] Upgrade stops before migration when the mandatory encrypted backup fails.
- [ ] A fresh encrypted database archive passes checksum and isolated restore.
- [ ] The age identity is recoverable by authorized operators if the application
      host is lost and is not stored only with the encrypted backup.
- [ ] Uploaded-object recovery is tested for the selected local or S3-compatible
      provider; database restore alone is not accepted.
- [ ] Measured RPO/RTO and retention are documented and fit the customer/SLA.
- [ ] Restore and rollback drills have named evidence and an operator who can
      execute them without repository authors.

## Security and access

- [ ] Initial administrator password is changed and removed from bootstrap
      configuration where supported.
- [ ] Least-privilege administrator, operator, auditor, and normal-user scenarios
      are tested; denied operations are rejected by the API.
- [ ] Session revocation, password recovery, CSRF, rate limits, and audit events
      pass the release test suite.
- [ ] Real delivery-provider and object-storage credentials are least-privilege,
      scoped to this installation, and successfully rotated in a drill.
- [ ] A minimal threat model and personal-data inventory identify trust
      boundaries, owners, retention, log masking, and incident actions.
- [ ] Private vulnerability reporting and the security response owner are live.

## Product workflows and UX

- [ ] Clean-install E2E covers sign-in, forced password change, navigation,
      users/roles, tasks, files, notifications, announcements, and System status.
- [ ] Authorization tests include cross-role and direct-API negative cases.
- [ ] Loading, empty, success, error, keyboard, screen-reader, and narrow-viewport
      states pass for critical workflows.
- [ ] A representative upload/download succeeds at the configured production
      size limit and interrupted upload behavior is understood.
- [ ] `DWH_FILE_SCANNER_REQUIRED=true`; the ClamAV EICAR test is rejected,
      scanner outage fails closed, and quarantine objects are removed.
- [ ] No unsafe placeholder provider or disabled module is presented as a
      working production capability.

## Operations

- [ ] Health, latency/error, capacity, certificate, backup age/failure, database,
      object storage, and delivery dead-letter alerts reach the on-call owner.
- [ ] Logs are retained, access-controlled, time-synchronized, searchable by
      request/audit identifiers, and verified free of secrets/personal payloads.
- [ ] Incident severity, escalation, maintenance window, customer communication,
      and security disclosure owners are named.
- [ ] Daily/weekly/monthly tasks in the
      [maintenance guide](maintenance-guide.md) are scheduled.
- [ ] A clean deployment on the target class of infrastructure runs for the
      agreed soak period without unexplained errors or resource growth.

## Explicit release record

Record one of:

- **GO:** every blocking item is evidenced; link the evidence bundle.
- **NO-GO:** list failed items, owner, and next review date.

There is no implied GO. As of this document update, the repository itself does
not contain target-environment evidence, an approved production SLO/SLA, or a
completed four-month launch sign-off; those remain installation-specific inputs.
