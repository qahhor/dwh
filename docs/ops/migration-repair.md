# SmartupCMS migration history repair

**Version:** 1.0

**Updated:** 2026-09-27

A released migration never changes and never disappears: a database keeps the
checksum of every file it applied, and an edited or deleted file stops it from
starting. Since plan 10/10 item 0.5, `MigrationManifestTest` holds the SHA-256
of every migration and fails the build on any edit or removal. A mistake in a
merged migration is corrected by a new migration.

Two files changed before that rule existed:

| File | Change | Date |
|---|---|---|
| `V100__fnd_core.sql` | a comment was rewritten after release | 2026-09-20 |
| `V101__fnd_system_user.sql` | deleted after release; the `system` account is created by code now | 2026-09-20 |

## Symptom

The migrate job or the server stops at start with a Flyway validation error
such as:

- `Migration checksum mismatch for migration version 100`;
- `Detected applied migration not resolved locally: 101`.

Only databases migrated before 2026-09-20 are affected. A database created
later validates as is.

## Repair

1. Take the encrypted pre-migration backup as in the
   [maintenance guide](maintenance-guide.md) and confirm it is readable.
2. Run the migrate job once with the repair switch:

   ```bash
   docker compose -f deploy/compose/docker-compose.prod.yml --env-file .env.production \
     --profile tools run --rm -e SMC_MIGRATE_REPAIR=true migrate
   ```

   The migrate profile runs `flyway repair` first: it realigns the checksums of
   applied migrations with the files and marks missing migrations as deleted.
   Then it migrates as usual. The log line `migration_history_repaired` shows
   how many rows were aligned and deleted.
3. Run the migrate job again **without** the switch. It must finish with no
   validation error.
4. Start the server and check readiness.

The repair touches `flyway_schema_history` only. Data created by the repaired
migrations stays: the `system` account from V101 remains and the code that now
creates it skips an existing one.

## Do not

- Do not keep `SMC_MIGRATE_REPAIR=true` in the environment file: repair hides
  any future edit of a released migration, which is what the manifest exists
  to catch.
- Do not delete rows from `flyway_schema_history` or edit checksums by hand.
- Do not restore the old text of V100 or the file V101: databases created after
  2026-09-20 would then fail the same way.
