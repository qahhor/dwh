set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: V033 granted the audit partition functions to PUBLIC, so any database role could detach a month of
--         audit_log, the current one included. Execution is now limited to roles that already write audit_log
--         (the application role gets that through the default privileges of the migrator), and detaching the
--         current or the previous month is refused.
-- approved_by: product owner (decision 2026-09-27, plan 10/10 item 0.3)

create or replace function audit_log_detach_partition(p_year int, p_month int)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_date date;
    v_part_name text;
    v_archived_name text;
begin
    if p_year < 2020 or p_year > 2100 then
        raise exception 'Invalid year: %', p_year using errcode = 'check_violation';
    end if;
    if p_month < 1 or p_month > 12 then
        raise exception 'Invalid month: %', p_month using errcode = 'check_violation';
    end if;

    v_date := make_date(p_year, p_month, 1);
    -- The current and the previous month stay attached whatever the caller asks: retention never goes below that.
    if v_date >= (date_trunc('month', now() at time zone 'UTC') - interval '1 month')::date then
        raise exception 'Partition % is too recent to detach', to_char(v_date, 'YYYY_MM')
            using errcode = 'check_violation';
    end if;

    v_part_name := 'audit_log_' || to_char(v_date, 'YYYY_MM');
    v_archived_name := 'audit_log_archived_' || to_char(v_date, 'YYYY_MM');

    if not exists (select 1 from pg_class where relname = v_part_name) then
        raise exception 'Partition table % does not exist', v_part_name using errcode = 'undefined_table';
    end if;

    execute format('alter table audit_log detach partition %I', v_part_name);
    execute format('alter table %I rename to %I', v_part_name, v_archived_name);
    execute format('revoke update, delete, truncate on %I from public', v_archived_name);
    execute format('grant select on %I to public', v_archived_name);

    return v_archived_name;
end;
$$;

revoke execute on function audit_log_create_partition(int, int) from public;
revoke execute on function audit_log_detach_partition(int, int) from public;

do $$
declare
    r record;
begin
    for r in
        select rolname from pg_roles
        where rolname <> current_user
          and not rolsuper
          and has_table_privilege(oid, 'audit_log', 'INSERT')
    loop
        execute format('grant execute on function audit_log_create_partition(int, int) to %I', r.rolname);
        execute format('grant execute on function audit_log_detach_partition(int, int) to %I', r.rolname);
    end loop;
end;
$$;
