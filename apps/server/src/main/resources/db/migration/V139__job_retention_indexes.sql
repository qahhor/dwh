set lock_timeout = '2s';
set statement_timeout = '0';
-- Plan 10/10, item 3.13 (ADR-0025): the retention job deletes queued jobs that used up their attempts and finished
-- search index jobs past their retention; these indexes serve its predicates. Built concurrently, outside a
-- transaction; an interrupted build leaves an invalid index to remove by hand before the migration runs again
-- (docs/ops/migration-repair.md).
create index concurrently if not exists fnd_job_queue_failed_at_idx
    on fnd_job_queue (failed_at) where failed_at is not null;
create index concurrently if not exists search_jobs_finished_at_idx
    on search_jobs (finished_at) where finished_at is not null;
