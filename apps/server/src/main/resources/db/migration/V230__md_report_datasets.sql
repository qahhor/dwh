set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0044: datasets an administrator defines for reports - a source entity, optionally at the grain of one table of
-- its document lines, the fields reports may use, a fixed filter and measures computed by formulas over aggregates.
-- A dataset holds keys and a filter of the list's own DSL, never SQL; each reader's scope and field rights apply when
-- a report runs. Saved reports refer to a dataset by its code, so the code never changes.
create table md_report_datasets (
    id bigint generated always as identity constraint md_report_datasets_pkey primary key,
    code text not null constraint md_report_datasets_ck_code check (code ~ '^[a-z][a-z0-9_]{0,39}$'),
    name_ru text not null constraint md_report_datasets_ck_name_ru check (char_length(name_ru) between 1 and 80),
    name_uz text not null constraint md_report_datasets_ck_name_uz check (char_length(name_uz) between 1 and 80),
    name_en text not null constraint md_report_datasets_ck_name_en check (char_length(name_en) between 1 and 80),
    entity_code text not null
        constraint md_report_datasets_ck_entity check (entity_code ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,3}$'),
    lines text constraint md_report_datasets_ck_lines check (lines ~ '^[a-z][a-zA-Z0-9]{0,63}$'),
    fields jsonb not null
        constraint md_report_datasets_ck_fields check (jsonb_typeof(fields) = 'array' and jsonb_array_length(fields) between 1 and 40),
    filter jsonb not null default '[]'::jsonb
        constraint md_report_datasets_ck_filter check (jsonb_typeof(filter) = 'array' and octet_length(filter::text) <= 16384),
    measures jsonb not null default '[]'::jsonb
        constraint md_report_datasets_ck_measures check (jsonb_typeof(measures) = 'array' and jsonb_array_length(measures) <= 8),
    revision bigint not null default 1
        constraint md_report_datasets_ck_revision check (revision between 1 and (power(2::numeric, 53) - 1)),
    created_by bigint not null constraint md_report_datasets_fk_created_by references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    modified_by bigint not null constraint md_report_datasets_fk_modified_by references md_users (id),
    modified_at timestamptz not null default clock_timestamp(),
    constraint md_report_datasets_uk_code unique (code)
);
create index md_report_datasets_entity_idx on md_report_datasets (entity_code);
create index md_report_datasets_page_idx on md_report_datasets (created_at desc, id desc);
create index md_report_datasets_created_by_idx on md_report_datasets (created_by);
create index md_report_datasets_modified_by_idx on md_report_datasets (modified_by);
