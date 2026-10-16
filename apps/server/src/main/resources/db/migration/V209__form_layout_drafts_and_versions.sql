set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0035: internal authoring storage, not a runtime EntityDefinition or an active definition registry.
-- Exact numeric arithmetic keeps revisions and versions within the JavaScript safe-integer range.
create table md_form_layout_drafts (
    entity_code text constraint md_form_layout_drafts_pkey primary key,
    declaration jsonb not null constraint md_form_layout_drafts_ck_declaration check (jsonb_typeof(declaration) = 'object'),
    revision bigint not null default 1 constraint md_form_layout_drafts_ck_revision check (revision between 1 and (power(2::numeric, 53) - 1)),
    created_by bigint not null constraint md_form_layout_drafts_fk_created_by references md_users (id),
    modified_by bigint not null constraint md_form_layout_drafts_fk_modified_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    modified_at timestamptz not null default clock_timestamp()
);
create index md_form_layout_drafts_created_by_idx on md_form_layout_drafts (created_by);
create index md_form_layout_drafts_modified_by_idx on md_form_layout_drafts (modified_by);

create table md_form_layout_versions (
    id bigint generated always as identity constraint md_form_layout_versions_pkey primary key,
    entity_code text not null constraint md_form_layout_versions_fk_entity references md_form_layout_drafts (entity_code),
    version bigint not null constraint md_form_layout_versions_ck_version check (version between 1 and (power(2::numeric, 53) - 1)),
    source_revision bigint not null constraint md_form_layout_versions_ck_revision check (source_revision between 1 and (power(2::numeric, 53) - 1)),
    checksum text not null constraint md_form_layout_versions_ck_checksum check (checksum ~ '^[0-9a-f]{64}$'),
    package jsonb not null constraint md_form_layout_versions_ck_package check (jsonb_typeof(package) = 'object'),
    created_by bigint not null constraint md_form_layout_versions_fk_created_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    constraint md_form_layout_versions_uk_entity_version unique (entity_code, version),
    constraint md_form_layout_versions_uk_source_revision unique (entity_code, source_revision)
);
create index md_form_layout_versions_page_idx on md_form_layout_versions (entity_code, created_at desc, id desc);
create index md_form_layout_versions_created_by_idx on md_form_layout_versions (created_by);

create function md_form_layout_version_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'Prepared form layout versions are immutable' using errcode = 'restrict_violation';
end;
$$;
create trigger md_form_layout_versions_no_change before update or delete on md_form_layout_versions
    for each row execute function md_form_layout_version_immutable();
create trigger md_form_layout_versions_no_truncate before truncate on md_form_layout_versions
    for each statement execute function md_form_layout_version_immutable();
