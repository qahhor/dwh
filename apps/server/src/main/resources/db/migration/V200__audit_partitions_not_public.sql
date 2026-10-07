set lock_timeout = '2s';
set statement_timeout = '60s';
-- The monthly audit_log partitions were open to PUBLIC: V033 granted select and insert on every partition it created,
-- and V125 still granted select on a detached one renamed to audit_log_archived_YYYY_MM, so any database role could
-- read a month of the audit log around the parent table's privileges. Reads and writes go through audit_log; the
-- archive reads a detached partition as the application role, which holds its privileges through the default
-- privileges of the migrator, the owner of the SECURITY DEFINER functions. Both functions now revoke everything from
-- PUBLIC, as the daily ones of V127 do, and the partitions that exist lose what PUBLIC held.

create or replace function audit_log_create_partition(p_year int, p_month int)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_date date;
    v_next_date date;
    v_part_name text;
begin
    if p_year < 2020 or p_year > 2100 then
        raise exception 'Invalid year: %', p_year using errcode = 'check_violation';
    end if;
    if p_month < 1 or p_month > 12 then
        raise exception 'Invalid month: %', p_month using errcode = 'check_violation';
    end if;

    v_date := make_date(p_year, p_month, 1);
    v_next_date := (v_date + interval '1 month')::date;
    v_part_name := 'audit_log_' || to_char(v_date, 'YYYY_MM');

    if not exists (select 1 from pg_class where relname = v_part_name) then
        execute format(
            'create table if not exists %I partition of audit_log for values from (%L) to (%L)',
            v_part_name,
            v_date::text || ' 00:00:00+00',
            v_next_date::text || ' 00:00:00+00'
        );
        -- Reads and writes go through audit_log; the partition itself is not open to anyone.
        execute format('revoke all on %I from public', v_part_name);
    end if;

    return v_part_name;
end;
$$;

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
    -- An archived month is read by the archive as the application role, never by PUBLIC.
    execute format('revoke all on %I from public', v_archived_name);

    return v_archived_name;
end;
$$;

do $$
declare
    r record;
begin
    for r in
        select c.relname
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public'
          and c.relkind in ('r', 'p')
          and c.relname ~ '^audit_log_(archived_)?[0-9]{4}_[0-9]{2}(_[0-9]{2})?$'
    loop
        execute format('revoke all on %I from public', r.relname);
    end loop;
end;
$$;
