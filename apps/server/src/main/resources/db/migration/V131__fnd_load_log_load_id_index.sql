set lock_timeout = '2s';
set statement_timeout = '0';
-- Plan 10/10, item 3.7: the index of fnd_load_log's foreign key, built concurrently on a large table (ADR-0020, rule 8);
-- Flyway runs this file outside a transaction. If the build is interrupted, see docs/ops/migration-repair.md.
create index concurrently if not exists fnd_load_log_load_id_idx on fnd_load_log (load_id);
