set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0043: the number of a live form's document, given by the server from a counter of the form per period (none,
-- a year or a month) in the transaction that creates the record or moves it to the status the form names. The number
-- is unique within the form, never changes once given and is never given again, as studio_form_counters (V224) keeps
-- the record's own number.
alter table studio_records
    add column doc_number text constraint studio_records_ck_doc_number check (char_length(doc_number) between 1 and 80);

create unique index studio_records_doc_number_uq on studio_records (form_id, doc_number);

create table studio_form_number_counters (
    form_id bigint not null constraint studio_form_number_counters_fk_form references studio_forms (id),
    period text not null constraint studio_form_number_counters_ck_period check (period ~ '^([0-9]{4}(-[0-9]{2})?)?$'),
    last_number bigint not null constraint studio_form_number_counters_ck_last_number check (last_number >= 1),
    constraint studio_form_number_counters_pkey primary key (form_id, period)
);

-- A given number stays: an update keeps the number the record has, whatever the statement sets.
create function studio_keep_doc_number() returns trigger language plpgsql as $$
begin
    if old.doc_number is not null then
        new.doc_number := old.doc_number;
    end if;
    return new;
end;
$$;
create trigger studio_records_doc_number before update on studio_records
for each row execute function studio_keep_doc_number();
