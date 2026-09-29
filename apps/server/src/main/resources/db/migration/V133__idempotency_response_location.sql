set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 3.4: a create answers 201 with Location; a replay under the same Idempotency-Key names the same
-- resource. Null for an answer without Location and for rows stored before this release.
alter table idempotency_keys add column response_location text;
