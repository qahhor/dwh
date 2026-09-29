set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 3.8: a job is leased in a short transaction and runs outside it.
-- next_run_at  — when the job may run next; a failed attempt moves it forward by the retry backoff.
-- locked_by    — the claim that holds the lease (node and claim id); null while the job waits.
-- locked_until — the end of the lease; the runner renews it while the handler works, and a job whose lease ran out
--                (the node died) is taken again.
-- failed_at    — the job used up its attempts; it stays in the queue for the operator and is never taken again.
-- run_at is superseded by next_run_at: nothing reads it any more, it is dropped in a later release (expand/contract).
-- Every queued row was due at once (run_at always took its default now()), so next_run_at = now() loses nothing.
alter table fnd_job_queue add column next_run_at timestamptz not null default now();
alter table fnd_job_queue add column locked_by text;
alter table fnd_job_queue add column locked_until timestamptz;
alter table fnd_job_queue add column failed_at timestamptz;
comment on column fnd_job_queue.run_at is 'Superseded by next_run_at (V132); dropped in a later release';

-- The attempt a run belongs to: the history of a retried job reads 1, 2, … up to the limit.
alter table fnd_job_runs add column attempt integer;

-- The claim looks for due jobs that have not failed; the queue stays small, a plain index is enough.
create index fnd_job_queue_next_run_at_idx on fnd_job_queue (next_run_at) where failed_at is null;
