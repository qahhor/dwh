set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0038: immutable create-only declarations; application requests never execute DDL.
create table studio_forms (
    id bigint generated always as identity constraint studio_forms_pkey primary key,
    code text not null constraint studio_forms_ck_code check (code ~ '^studio\.[a-z][a-z0-9_]{0,19}$'),
    declaration jsonb not null constraint studio_forms_ck_declaration check (jsonb_typeof(declaration) = 'object'),
    checksum text not null constraint studio_forms_ck_checksum check (checksum ~ '^[0-9a-f]{64}$'),
    created_by bigint not null constraint studio_forms_fk_created_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    constraint studio_forms_uk_code unique (code)
);
create index studio_forms_created_by_idx on studio_forms (created_by);
