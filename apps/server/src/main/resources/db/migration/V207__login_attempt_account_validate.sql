set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0020: validate the journal's foreign key separately from adding the column.
alter table kauth_login_attempts validate constraint kauth_login_attempts_fk_user;
