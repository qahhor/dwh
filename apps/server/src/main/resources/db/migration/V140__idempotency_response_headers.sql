set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 3.4: a replay under the same Idempotency-Key answers with the Content-Type and the ETag of the
-- original answer. Null for an answer without a body or without ETag, and for rows stored before this release.
alter table idempotency_keys
    add column response_content_type text,
    add column response_etag text;
