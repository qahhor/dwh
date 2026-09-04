-- Stable keyset traversal for security events ordered newest first.
-- audit_log already has the equivalent composite primary key (changed_at, id).
create index if not exists security_events_created_at_id_idx
    on security_events (created_at desc, id desc);
