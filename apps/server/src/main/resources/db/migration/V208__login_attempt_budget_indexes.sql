set lock_timeout = '2s';
set statement_timeout = '0';
-- ADR-0020: journal indexes are built outside a transaction. An interrupted build may leave an invalid index;
-- remove that invalid index before retrying this migration.
create index concurrently kauth_login_attempts_user_id_attempt_at_idx
    on kauth_login_attempts (user_id, attempt_at desc);
create index concurrently kauth_login_attempts_login_attempt_at_idx
    on kauth_login_attempts (login, attempt_at desc) where user_id is null and not is_success;
