set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0047: solution packages. An import keeps the verified package an administrator uploaded, or a template the
-- product ships, with the outcome of its last check per item; once applied, who applied it and how the roles and users
-- the package names were mapped to this installation's. A package carries no record data, no user ids and no secret.
-- A check that is never applied expires; an applied import stays as the history of the installation.
create table studio_package_imports (
    id bigint generated always as identity constraint studio_package_imports_pkey primary key,
    code text not null constraint studio_package_imports_ck_code check (code ~ '^[a-z][a-z0-9-]{1,39}$'),
    version text not null
        constraint studio_package_imports_ck_version check (version ~ '^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,6}$'),
    title jsonb not null constraint studio_package_imports_ck_title check (jsonb_typeof(title) = 'object'),
    checksum text not null constraint studio_package_imports_ck_checksum check (checksum ~ '^[0-9a-f]{64}$'),
    source text not null constraint studio_package_imports_ck_source check (source in ('upload', 'template')),
    suffix text constraint studio_package_imports_ck_suffix check (suffix ~ '^_[a-z0-9]{1,5}$'),
    package jsonb not null constraint studio_package_imports_ck_package check (jsonb_typeof(package) = 'object'),
    items jsonb not null default '[]'::jsonb
        constraint studio_package_imports_ck_items check (jsonb_typeof(items) = 'array'),
    status text not null default 'preview'
        constraint studio_package_imports_ck_status check (status in ('preview', 'applied')),
    mapping jsonb not null default '{}'::jsonb
        constraint studio_package_imports_ck_mapping check (jsonb_typeof(mapping) = 'object'),
    created_by bigint not null constraint studio_package_imports_fk_created_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    checked_at timestamptz not null default clock_timestamp(),
    applied_by bigint constraint studio_package_imports_fk_applied_by references md_users (id),
    applied_at timestamptz,
    constraint studio_package_imports_ck_applied check (
        (status = 'applied') = (applied_by is not null and applied_at is not null))
);
create index studio_package_imports_created_idx on studio_package_imports (created_at desc, id desc);
create index studio_package_imports_created_by_idx on studio_package_imports (created_by);
create index studio_package_imports_applied_by_idx on studio_package_imports (applied_by);

-- What an applied package created that has no code of its own: a saved report, a mailing or an automation, by the
-- package's code (with the suffix it was applied under) and the item's key, so that applying the same package again
-- finds it instead of creating a second one. The checksum is the item's as the package carried it.
create table studio_package_objects (
    id bigint generated always as identity constraint studio_package_objects_pkey primary key,
    package_code text not null
        constraint studio_package_objects_ck_package_code check (
            package_code ~ '^[a-z][a-z0-9-]{1,39}(_[a-z0-9]{1,5})?$'),
    kind text not null constraint studio_package_objects_ck_kind check (kind in ('report', 'mailing', 'automation')),
    item_key text not null constraint studio_package_objects_ck_item_key check (item_key ~ '^[a-z][a-z0-9_-]{0,39}$'),
    object_id bigint not null,
    checksum text not null constraint studio_package_objects_ck_checksum check (checksum ~ '^[0-9a-f]{64}$'),
    import_id bigint not null constraint studio_package_objects_fk_import references studio_package_imports (id),
    modified_at timestamptz not null default clock_timestamp(),
    constraint studio_package_objects_uk_item unique (package_code, kind, item_key)
);
create index studio_package_objects_import_idx on studio_package_objects (import_id);
