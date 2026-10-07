# SmartupCMS migration failure repair

**Version:** 2.0

**Updated:** 2026-10-03

A released migration never changes and never disappears: a database keeps the
checksum of every file it applied, and `MigrationManifestTest` holds the
SHA-256 of every migration and fails the build on any edit or removal (plan
10/10, item 0.5). A mistake in a merged migration is corrected by a new
migration. There is no switch that rewrites the migration history: the former
`SMC_MIGRATE_REPAIR` served databases migrated before 2026-09-20, and no such
installation exists (AGENTS.md, «No client installations exist before the
final release»).

This runbook covers the two failures a migration may leave behind on purpose:
an interrupted concurrent index build and a constraint that does not validate.
In both cases the migration is recorded in `flyway_schema_history` as failed
(`success = false`), and the migrate job refuses to run until that row is gone.
Remove only that row, as the migrator role, after fixing the cause:

```sql
delete from flyway_schema_history where version = '<NNN>' and not success;
```

Never delete a successful row and never edit a checksum.

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

Then remove the failed row as shown above and start the migration again.

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
`search_settings.updated_by`. After the correction, remove the failed row as shown
above and run the migration again.
