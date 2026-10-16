set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0038: declarations cannot be edited or removed by the runtime DML role.
create function studio_reject_declaration_change() returns trigger language plpgsql as $$
begin
    raise exception 'Activated declarations are immutable' using errcode = 'restrict_violation';
end;
$$;
create trigger studio_forms_immutable before update or delete on studio_forms
for each row execute function studio_reject_declaration_change();

create trigger studio_forms_no_truncate before truncate on studio_forms
for each statement execute function studio_reject_declaration_change();
