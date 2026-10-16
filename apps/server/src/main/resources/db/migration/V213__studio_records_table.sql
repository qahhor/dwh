set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0038: shared records have a mandatory server-owned form partition.
create table studio_records (
    id bigint generated always as identity constraint studio_records_pkey primary key,
    form_id bigint not null constraint studio_records_fk_form references studio_forms (id),
    payload jsonb not null default '{}'::jsonb constraint studio_records_ck_payload check (jsonb_typeof(payload) = 'object'),
    name text not null constraint studio_records_ck_name check (char_length(name) between 1 and 255),
    code text not null constraint studio_records_ck_code check (code ~ '^[a-z0-9_-]{1,64}$'),
    attributes jsonb not null default '{}'::jsonb
        constraint studio_records_ck_attributes check (jsonb_typeof(attributes) = 'object'),
    archived_at timestamptz,
    archived_by bigint constraint studio_records_fk_archived_by references md_users (id),
    created_by bigint not null constraint studio_records_fk_created_by references md_users (id),
    modified_by bigint not null constraint studio_records_fk_modified_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    modified_at timestamptz not null default clock_timestamp(),
    revision bigint not null default 1
);

create unique index studio_records_code_uq on studio_records (form_id, code) where archived_at is null;
create index studio_records_archived_by_idx on studio_records (archived_by);
create index studio_records_created_by_idx on studio_records (created_by);
create index studio_records_modified_by_idx on studio_records (modified_by);

create index studio_records_form_page_idx on studio_records (form_id, modified_at desc, id desc);
