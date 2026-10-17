set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0041: the mutable state of a live form; without a row a form serves version 1 and is active.
create table studio_form_state (
    form_id bigint constraint studio_form_state_pkey primary key
        constraint studio_form_state_fk_form references studio_forms (id),
    active_version integer not null
        constraint studio_form_state_ck_active_version check (active_version between 1 and 100),
    status text not null constraint studio_form_state_ck_status check (status in ('active', 'archived')),
    revision bigint not null
        constraint studio_form_state_ck_revision check (revision between 2 and (power(2::numeric, 53) - 1)),
    modified_by bigint not null constraint studio_form_state_fk_modified_by references md_users (id),
    modified_at timestamptz not null default clock_timestamp()
);
create index studio_form_state_modified_by_idx on studio_form_state (modified_by);

-- A state never points at a version that does not exist; version 1 is the form row itself.
create function studio_check_active_version() returns trigger language plpgsql as $$
begin
    if new.active_version > 1 and not exists (
        select 1 from studio_form_versions v where v.form_id = new.form_id and v.version = new.active_version
    ) then
        raise exception 'Unknown live form version' using errcode = 'foreign_key_violation';
    end if;
    return new;
end;
$$;
create trigger studio_form_state_active_version before insert or update on studio_form_state
for each row execute function studio_check_active_version();

-- A state row stays while its form does: the history of activations and archives is kept.
create trigger studio_form_state_no_delete before delete on studio_form_state
for each row execute function studio_reject_declaration_change();

create trigger studio_form_state_no_truncate before truncate on studio_form_state
for each statement execute function studio_reject_declaration_change();
