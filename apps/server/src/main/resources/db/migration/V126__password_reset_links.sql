set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: kauth_password_reset_codes was never written: the reset code used a table that does not exist. It is
--         cleared anyway before a link becomes bound to the user's authentication generation and to the channel
--         it was sent to, both mandatory.
-- approved_by: product owner (decision 2026-09-27, plan 10/10 item 0.1)

delete from kauth_password_reset_codes;

alter table kauth_password_reset_codes
    add column auth_version bigint not null check (auth_version >= 0),
    add column channel text not null check (channel in ('email', 'telegram')),
    add column used_at timestamptz;

create index kauth_password_reset_codes_user_idx on kauth_password_reset_codes (user_id, created_at desc);
