set lock_timeout = '2s';
set statement_timeout = '60s';
-- A table as ADR-0020 wants it; checked by MigrationLintTest.
create table md_lint_sample (
    id bigint generated always as identity,
    owner_id bigint not null,
    code text not null,
    seen_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    modified_at timestamptz,
    constraint md_lint_sample_pkey primary key (id),
    constraint md_lint_sample_uk_code unique (code),
    constraint md_lint_sample_ck_code check (code <> ''),
    constraint md_lint_sample_fk_owner foreign key (owner_id) references md_users (id)
);
create index md_lint_sample_owner_idx on md_lint_sample (owner_id);
create unique index md_lint_sample_seen_uq on md_lint_sample (seen_at, owner_id);
