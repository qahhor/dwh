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

## An interrupted concurrent index build

A file that builds indexes concurrently (V129 is the first) runs outside a
transaction (ADR-0020, rule 8). If the build is interrupted (a lock wait over
`lock_timeout`, a restart, a cancelled session), PostgreSQL keeps the index as
invalid and Flyway records the migration as failed. Such an index is not used
by queries, and `create index concurrently if not exists` would skip it on the
next run. Before running the migration again, find and remove the invalid
indexes:

```sql
select indexrelid::regclass from pg_index where not indisvalid;
drop index concurrently <name>;
```

Then clear the failed row with `flyway repair` (it only removes failed
entries) and start the migration again.

## A constraint that does not validate (V150)

V149 adds foreign keys and `attributes` checks as `not valid`; V150 validates
the existing rows (plan 10/10, item 4.6). The application never writes a row
that breaks them, so a failure means a row was changed by hand. The error
names the constraint, for example:

- `insert or update on table "upl_packages" violates foreign key constraint
  "upl_packages_fk_uploaded_by"`;
- `check constraint "ms_tasks_ck_attributes" of relation "ms_tasks" is violated
  by some row`.

Find the rows and decide with the data owner how to correct them; V150 only
validates and never changes data:

```sql
select id, uploaded_by_id from upl_packages p
where not exists (select 1 from md_users u where u.id = p.uploaded_by_id);
select id, load_id from upl_packages p
where load_id is not null and not exists (select 1 from fnd_loads l where l.id = p.load_id);
select id, actor_id from search_jobs j
where actor_id is not null and not exists (select 1 from md_users u where u.id = j.actor_id);
select id from ms_tasks where jsonb_typeof(attributes) <> 'object';
```

The same query with another table finds the rows of the other checks
(`md_users`, `md_installed_modules`, `ms_notes`, `ms_task_projects`) and of
`search_settings.updated_by`. After the correction, clear the failed row with
`flyway repair` and run the migration again.
