set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: responses carrying a one-time secret (a new API token, a webhook signing key) were stored in
--         idempotency_keys for replay, because the filter excluded a path that does not exist. The handlers
--         are now marked @ReturnsSecret and never stored; this removes what was stored before.
-- approved_by: product owner (decision 2026-09-27, plan 10/10 item 0.2)

delete from idempotency_keys
where response_body ? 'rawSecretToken'
   or response_body ? 'secretToken';
