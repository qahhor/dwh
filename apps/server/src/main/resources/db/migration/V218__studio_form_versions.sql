set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0041: the later versions of a live form, immutable like version 1, which stays the studio_forms row itself.
create table studio_form_versions (
    id bigint generated always as identity constraint studio_form_versions_pkey primary key,
    form_id bigint not null constraint studio_form_versions_fk_form references studio_forms (id),
    version integer not null constraint studio_form_versions_ck_version check (version between 2 and 100),
    declaration jsonb not null
        constraint studio_form_versions_ck_declaration check (jsonb_typeof(declaration) = 'object'),
    checksum text not null constraint studio_form_versions_ck_checksum check (checksum ~ '^[0-9a-f]{64}$'),
    created_by bigint not null constraint studio_form_versions_fk_created_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    constraint studio_form_versions_uk_form_version unique (form_id, version)
);
create index studio_form_versions_created_by_idx on studio_form_versions (created_by);

create trigger studio_form_versions_immutable before update or delete on studio_form_versions
for each row execute function studio_reject_declaration_change();

create trigger studio_form_versions_no_truncate before truncate on studio_form_versions
for each statement execute function studio_reject_declaration_change();
