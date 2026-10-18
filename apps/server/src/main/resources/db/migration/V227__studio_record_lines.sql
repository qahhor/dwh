set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0043: the lines of a live form's documents. A form has up to three tables of lines; the table of its place in
-- the declaration (1 to 3) keeps the rows of every form, each row reached only through its record, which the runtime
-- reads within the record's form partition. A row is a JSON object of at most 16 KiB; deleting a record deletes its
-- rows.
create table studio_record_lines_1 (
    id bigint generated always as identity constraint studio_record_lines_1_pkey primary key,
    record_id bigint not null constraint studio_record_lines_1_fk_record references studio_records (id) on delete cascade,
    position integer not null constraint studio_record_lines_1_ck_position check (position between 1 and 500),
    payload jsonb not null default '{}'::jsonb
        constraint studio_record_lines_1_ck_payload check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 16384)
);
create index studio_record_lines_1_record_id_idx on studio_record_lines_1 (record_id, position);

create table studio_record_lines_2 (
    id bigint generated always as identity constraint studio_record_lines_2_pkey primary key,
    record_id bigint not null constraint studio_record_lines_2_fk_record references studio_records (id) on delete cascade,
    position integer not null constraint studio_record_lines_2_ck_position check (position between 1 and 500),
    payload jsonb not null default '{}'::jsonb
        constraint studio_record_lines_2_ck_payload check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 16384)
);
create index studio_record_lines_2_record_id_idx on studio_record_lines_2 (record_id, position);

create table studio_record_lines_3 (
    id bigint generated always as identity constraint studio_record_lines_3_pkey primary key,
    record_id bigint not null constraint studio_record_lines_3_fk_record references studio_records (id) on delete cascade,
    position integer not null constraint studio_record_lines_3_ck_position check (position between 1 and 500),
    payload jsonb not null default '{}'::jsonb
        constraint studio_record_lines_3_ck_payload check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 16384)
);
create index studio_record_lines_3_record_id_idx on studio_record_lines_3 (record_id, position);
