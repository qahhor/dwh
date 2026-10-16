set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032: retained responses carry the records whose access must be checked again.
alter table idempotency_keys add column response_access jsonb not null default '{}'::jsonb;
