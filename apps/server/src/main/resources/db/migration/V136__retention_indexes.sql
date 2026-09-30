set lock_timeout = '2s';
set statement_timeout = '0';
-- Plan 10/10, item 3.13: the retention job deletes the rows of journal tables past their retention in short batches.
-- These indexes serve its predicates (RetentionPolicy beans), so a run on a table with nothing to delete reads an
-- index, not the whole table. security_events (created_at) and kwh_logs (sent_at) have theirs already. Built
-- concurrently, outside a transaction; an interrupted build leaves an invalid index to remove by hand before the
-- migration runs again (docs/ops/migration-repair.md).
create index concurrently if not exists kauth_login_attempts_attempt_at_idx on kauth_login_attempts (attempt_at);
create index concurrently if not exists kauth_otp_codes_expires_at_idx on kauth_otp_codes (expires_at);
create index concurrently if not exists kauth_password_reset_codes_expires_at_idx
    on kauth_password_reset_codes (expires_at);
create index concurrently if not exists kauth_sessions_closed_at_idx
    on kauth_sessions (closed_at) where closed_at is not null;
create index concurrently if not exists kwh_outbox_processed_at_idx
    on kwh_outbox (processed_at) where status in ('SENT', 'DEAD_LETTER');
create index concurrently if not exists ms_notification_outbox_processed_at_idx
    on ms_notification_outbox (processed_at) where status in ('SENT', 'DEAD_LETTER');
create index concurrently if not exists ms_notifications_created_at_idx on ms_notifications (created_at);
