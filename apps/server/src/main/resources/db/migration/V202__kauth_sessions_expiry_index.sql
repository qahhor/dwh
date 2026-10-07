set lock_timeout = '2s';
set statement_timeout = '0';
-- Plan 10/10, item 7.5 (ADR-0034): the housekeeping worker closes open sessions idle past the timeout or older than
-- the absolute lifetime. The index covers open sessions only, so its scan reads the few open rows instead of every
-- session kept for retention. Built concurrently, outside a transaction; an interrupted build leaves an invalid
-- index to remove by hand before the migration runs again (docs/ops/migration-repair.md).
create index concurrently if not exists kauth_sessions_open_last_seen_idx
    on kauth_sessions (last_seen_at, created_at) where closed_at is null;
