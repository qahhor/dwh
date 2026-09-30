set lock_timeout = '2s';
set statement_timeout = '0';
-- Plan 10/10, item 3.13: the retention job deletes finished job runs past their retention; this index serves its
-- predicate. Built concurrently, outside a transaction (docs/ops/migration-repair.md).
create index concurrently if not exists fnd_job_runs_finished_at_idx
    on fnd_job_runs (finished_at) where finished_at is not null;
