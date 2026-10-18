set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0041: a live form of format 4 names its records by a field of its own or by their number, without the system
-- name and code; the two columns stay for the forms that keep them, whose runtime still requires both.
alter table studio_records
    alter column name drop not null,
    alter column code drop not null,
    add column record_number bigint constraint studio_records_ck_record_number check (record_number >= 1);

-- The last number given to a record of each form; its row lock keeps two records of one form from one number.
create table studio_form_counters (
    form_id bigint constraint studio_form_counters_pkey primary key
        constraint studio_form_counters_fk_form references studio_forms (id),
    last_number bigint not null constraint studio_form_counters_ck_last_number check (last_number >= 1)
);

create unique index studio_records_number_uq on studio_records (form_id, record_number);

-- Every new record takes the next number of its form; a number never changes afterwards. Records saved before this
-- migration have none.
create function studio_number_record() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' then
        new.record_number := old.record_number;
        return new;
    end if;
    insert into studio_form_counters as c (form_id, last_number) values (new.form_id, 1)
    on conflict (form_id) do update set last_number = c.last_number + 1
    returning c.last_number into new.record_number;
    return new;
end;
$$;
create trigger studio_records_number before insert or update on studio_records
for each row execute function studio_number_record();
