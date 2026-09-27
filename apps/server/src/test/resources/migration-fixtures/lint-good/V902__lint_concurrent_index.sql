set lock_timeout = '2s';
set statement_timeout = '0';
-- An index on a large table: built concurrently, alone in its file, with no statement timeout (ADR-0020).
create index concurrently if not exists security_events_type_idx on security_events (event_type);
