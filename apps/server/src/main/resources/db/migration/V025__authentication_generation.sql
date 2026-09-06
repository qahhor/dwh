alter table md_users add column auth_version bigint not null default 0
    check (auth_version >= 0);
alter table kauth_sessions add column auth_version bigint not null default 0
    check (auth_version >= 0);
alter table kauth_api_tokens add column auth_version bigint not null default 0
    check (auth_version >= 0);
alter table kauth_otp_codes add column auth_version bigint not null default 0
    check (auth_version >= 0);
