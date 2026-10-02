set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032, 10.1 (plan 10/10, item 5.8): the journal of entity imports, kept by the report module next to its exports
-- (ADR-0018). An import belongs to the person who started it: the uploaded file of the files module, the mode (a dry
-- run checks every row and writes nothing, apply upserts by the entity's import key), the state of its job, the
-- counters and the checkpoint of the rows done (a retried job goes on after it), and the report file in the instance
-- storage. A week after its start the cleanup job removes the report file and the row; its row errors go with it.
create table report_imports (
    id bigint generated always as identity constraint report_imports_pkey primary key,
    public_id uuid not null default gen_random_uuid(),
    user_id bigint not null constraint report_imports_fk_user references md_users (id) on delete cascade,
    entity_code text not null
        constraint report_imports_ck_entity_code check (entity_code ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    file_id uuid not null,
    mode text not null constraint report_imports_ck_mode check (mode in ('dry_run', 'apply')),
    lang text constraint report_imports_ck_lang check (char_length(lang) <= 16),
    state text not null default 'queued'
        constraint report_imports_ck_state check (state in ('queued', 'running', 'done', 'failed')),
    rows_total integer constraint report_imports_ck_rows_total check (rows_total >= 0),
    rows_done integer not null default 0 constraint report_imports_ck_rows_done check (rows_done >= 0),
    created_count integer not null default 0 constraint report_imports_ck_created check (created_count >= 0),
    updated_count integer not null default 0 constraint report_imports_ck_updated check (updated_count >= 0),
    failed_count integer not null default 0 constraint report_imports_ck_failed check (failed_count >= 0),
    error_code text constraint report_imports_ck_error_code check (char_length(error_code) <= 64),
    report_key text constraint report_imports_ck_report_key check (char_length(report_key) <= 255),
    report_size bigint constraint report_imports_ck_report_size check (report_size >= 0),
    created_at timestamptz not null default clock_timestamp(),
    started_at timestamptz,
    finished_at timestamptz,
    expires_at timestamptz not null default clock_timestamp() + interval '7 days'
);

create unique index report_imports_public_id_uq on report_imports (public_id);
create index report_imports_user_id_idx on report_imports (user_id, created_at desc);
create index report_imports_expires_at_idx on report_imports (expires_at);

-- One problem of a row of an import, addressed rows[17].qty, with its text in the import's language.
create table report_import_errors (
    id bigint generated always as identity constraint report_import_errors_pkey primary key,
    import_id bigint not null
        constraint report_import_errors_fk_import references report_imports (id) on delete cascade,
    row_no integer not null constraint report_import_errors_ck_row_no check (row_no >= 1),
    field text not null constraint report_import_errors_ck_field check (char_length(field) <= 200),
    code text not null constraint report_import_errors_ck_code check (char_length(code) <= 64),
    message text not null constraint report_import_errors_ck_message check (char_length(message) <= 1000)
);

create index report_import_errors_import_id_idx on report_import_errors (import_id, row_no, id);
