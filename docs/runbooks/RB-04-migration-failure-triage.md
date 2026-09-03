# RB-04: Migration failure triage

**Version:** 2.0

**Updated:** 2026-09-03

**Applies to:** the supported Docker Compose deployment described by
[ADR-0014](../adr/ADR-0014-unified-open-source-runtime.md).

## Trigger and objective

Use this runbook when the `migrate` service exits non-zero or schema readiness
blocks `server`. The objective is to protect authoritative PostgreSQL data,
choose a safe forward fix or restore, and record the actual recovery outcome.

## Immediate action

1. Stop the deployment. Do not start the new `server` or continue rollout.
2. Do not edit an applied Flyway file and do not run manual destructive SQL.
3. Preserve the migration output and relevant service events in the restricted
   incident record. Redact passwords, connection strings, tokens, environment
   values, customer content, and other secrets before sharing logs.
4. Record the incident start time, affected installation, release tag and image
   digest, expected migration version, current availability, and decision owner.
5. Confirm that the verified encrypted pre-migration backup and its checksum are
   available before attempting a recovery that may modify data.

## Diagnosis

Run the migration once from the correct deployment directory and retain its
exit code and secret-safe output:

```powershell
docker compose run --rm migrate
```

For a production bundle, use its configured Compose file and environment file;
do not substitute development credentials. Then:

1. Inspect `docker compose ps` and the PostgreSQL health status.
2. Through an operator-controlled, authenticated PostgreSQL session, inspect
   Flyway history without exposing credentials:

   ```sql
   select installed_rank, version, description, type, script,
          checksum, installed_on, execution_time, success
   from flyway_schema_history
   order by installed_rank;
   ```

3. Compare the failed version and checksum with the immutable release artifact.
   Determine whether Flyway recorded the migration and whether PostgreSQL rolled
   back its transaction.
4. Check database connectivity, DNS/network reachability inside the Compose
   network, database role permissions, connection limits, locks, statement
   errors, and available disk/inode capacity for PostgreSQL and Docker.
5. Identify the failure class: configuration/precondition, an idempotent
   interrupted operation, migration defect requiring a forward fix, or
   data/schema incompatibility requiring restore.

Do not mark or delete a Flyway history row to make validation pass. Escalate any
uncertain database state to the database/release owner.

## Recovery decision

Choose exactly one branch and record the decision owner and rationale.

### A. Correct configuration and retry

Use this branch only when the SQL artifact is correct and the failure is a
configuration, connectivity, capacity, or permission issue. Correct the
precondition, prove that the migration is idempotent or that PostgreSQL rolled
it back completely, and rerun the normal `migrate` service. Do not retry an
unknown partially applied operation.

### B. Deploy a corrected forward migration

Use this branch when released migration logic is wrong but the current data can
be advanced safely. Keep every previously published Flyway file unchanged.
Review and release a new migration with a new version, an integration test, and
a documented compatibility window; deploy it through the normal fail-closed
release process.

### C. Restore and roll back the release

Use this branch when the schema/data state is incompatible and a safe forward
fix cannot meet the incident objective. Keep the deployment stopped and perform
these steps in order:

1. Identify the exact previous verified release tag. Set `APP_VERSION` in
   `.env.production` to that tag **before** invoking `scripts/prod/restore.ps1`
   or `scripts/prod/restore.sh`.
2. Pull the images selected by that environment, list the resolved service
   images, and inspect their repository digests:

   ```powershell
   docker compose -f deploy/compose/docker-compose.prod.yml --env-file .env.production pull server web backup postgres typesense
   docker compose -f deploy/compose/docker-compose.prod.yml --env-file .env.production config --images
   docker image inspect <each-image-reference> --format '{{json .RepoDigests}}'
   ```

   Record the resolved digests and verify each one against the previous
   release's verified manifest/signature evidence. Stop if any digest is absent
   or differs. Compose interpolates the tag from `APP_VERSION`; the restore
   scripts do not consume a raw digest argument.
3. Verify the encrypted pre-migration backup checksum, catalog, timestamp,
   decryption identity, and approved data-loss window. Then invoke the
   [restore procedure](../ops/maintenance-guide.md) in an operator-controlled
   environment using `scripts/prod/restore.ps1` or `scripts/prod/restore.sh`.
4. Restore object data when required for consistency. After the script starts
   services, resolve and verify the running image digests again before reopening
   traffic.

Restoring loses changes after the selected backup. The decision owner must
approve that data-loss window against the installation RPO before execution.

## Validation before reopening traffic

1. Run the Flyway migration path and confirm exit code 0 and a successful,
   checksum-consistent `flyway_schema_history`.
2. Start the selected server/web release and confirm server readiness plus
   healthy mandatory Compose services.
3. Run public health smoke checks and the critical Playwright journeys for
   sign-in, tasks, announcements, files, and system status as applicable.
4. Compare agreed pre/post data counts and domain invariants. Sample critical
   records and verify that expected audit entries remain present; never copy
   customer rows into a public incident report.
5. Confirm Typesense can be rebuilt from authorized PostgreSQL data and that
   search results remain server-authorized.
6. Keep traffic closed and return to diagnosis if any validation fails.

## Required incident evidence

The final restricted incident record must contain:

- incident, detection, decision, recovery-start, recovery-end, and validation
  timestamps in UTC;
- installation identifier, release tag, release/image digest, migration version,
  script checksum, and Flyway result;
- encrypted backup identifier and SHA-256 checksum, without the decryption
  identity or storage credentials;
- diagnosis, selected recovery branch, decision owner, operators, and approvals;
- secret-safe command outputs for migration, readiness, smoke/E2E, audit checks,
  and agreed data counts;
- expected and measured RPO/RTO, including the actual data-loss window and
  service restoration time;
- follow-up owner and due date for the forward fix, test, or runbook correction.

See the [database migration guide](../guidelines/database-migrations.md) for
authoring rules and the [rollback procedure](../ops/rollback.md) for
application-only rollback compatibility checks.
