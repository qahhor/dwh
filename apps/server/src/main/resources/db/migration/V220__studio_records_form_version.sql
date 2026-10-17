set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0041: a record keeps the version of its form it was saved with; reads project it onto the active version.
alter table studio_records
    add column form_version integer not null default 1
        constraint studio_records_ck_form_version check (form_version between 1 and 100);

-- Every write is saved with the active version: the database stamps it, so no write path can miss it.
create function studio_stamp_record_version() returns trigger language plpgsql as $$
begin
    new.form_version := coalesce(
        (select s.active_version from studio_form_state s where s.form_id = new.form_id), 1);
    return new;
end;
$$;
create trigger studio_records_form_version before insert or update on studio_records
for each row execute function studio_stamp_record_version();
