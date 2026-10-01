set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032, 4.7 (plan 10/10, item 5.2): the files of the file and image fields of entities. A row attaches a stored
-- file to one field of one record of an entity; the file is read through its record, so the files list keeps its own
-- rule (the owner's files) and is not widened to the records of every entity. An attached file cannot be deleted
-- (on delete restrict): the record would point at nothing. The record's deletion removes its rows; a file without
-- rows goes with the existing cleanup of the files module.
create table mf_record_files (
    entity     text not null constraint mf_record_files_ck_entity check (char_length(entity) <= 128),
    record_id  bigint not null,
    field_key  text not null constraint mf_record_files_ck_field_key check (field_key ~ '^[a-z][a-zA-Z0-9]{0,63}$'),
    file_id    uuid not null constraint mf_record_files_fk_file references mf_files (id) on delete restrict,
    created_at timestamptz not null default clock_timestamp(),
    constraint mf_record_files_pkey primary key (entity, record_id, field_key, file_id)
);
create index mf_record_files_file_id_idx on mf_record_files (file_id);
