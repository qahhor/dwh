set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 0.6: aliases of one account share its password-guess budget after a login or email change.
alter table kauth_login_attempts add column user_id bigint;
alter table kauth_login_attempts
    add constraint kauth_login_attempts_fk_user foreign key (user_id)
    references md_users(id) on delete set null not valid;
