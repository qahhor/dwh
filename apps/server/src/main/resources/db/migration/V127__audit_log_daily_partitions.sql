set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: audit_log is archived weekly or at 100 MB (decision of 2026-09-27), so its partitions become daily. The
--         empty future monthly partitions that V011 created up to 2027-12 would overlap the daily ones and are
--         dropped; a monthly partition that already holds a row stays. Current and past months stay monthly and are
--         archived whole. A partition is dropped only by audit_log_drop_archived_partition, and only when a verified
--         archive holds it.
-- approved_by: product owner (decision 2026-09-27: archive weekly or at 100 MB, archives kept 3 months, deletion
--              from the database configurable)

-- ---------- archives written, and the partitions each one holds ----------
create table audit_log_archives (
    id bigint generated always as identity primary key,
    file_key text not null,
    storage text not null,
    period_from timestamptz not null,
    period_to timestamptz not null,
    row_count bigint not null,
    byte_size bigint not null,
    sha256 text not null,
    created_at timestamptz not null default now(),
    verified_at timestamptz,
    file_deleted_at timestamptz,
    constraint audit_log_archives_uk_file_key unique (file_key),
    constraint audit_log_archives_ck_storage check (storage in ('local', 's3')),
    constraint audit_log_archives_ck_period check (period_from < period_to),
    constraint audit_log_archives_ck_counts check (row_count >= 0 and byte_size >= 0)
);

create table audit_log_archive_partitions (
    archive_id bigint not null,
    partition_name text not null,
    row_count bigint not null,
    dropped_at timestamptz,
    constraint audit_log_archive_partitions_pkey primary key (archive_id, partition_name),
    constraint audit_log_archive_partitions_fk_archive foreign key (archive_id)
        references audit_log_archives (id) on delete cascade,
    -- A partition goes into one archive; a failed, unverified archive is removed before the next try.
    constraint audit_log_archive_partitions_uk_partition unique (partition_name)
);

comment on table audit_log_archives is
    'Archive files of audit_log partitions (gzip JSON lines), in local storage or S3. verified_at: read back and '
    'matched by SHA-256 and row count; file_deleted_at: removed by the archive retention.';

-- ---------- daily partitions ----------
create or replace function audit_log_create_day_partition(p_day date)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_month_name text;
    v_name text;
begin
    if p_day is null or extract(year from p_day) < 2020 or extract(year from p_day) > 2100 then
        raise exception 'Invalid day: %', p_day using errcode = 'check_violation';
    end if;

    -- A month that is still monthly covers the day already: a daily partition would overlap it.
    v_month_name := 'audit_log_' || to_char(p_day, 'YYYY_MM');
    if exists (select 1 from pg_class where relname = v_month_name) then
        return v_month_name;
    end if;

    v_name := 'audit_log_' || to_char(p_day, 'YYYY_MM_DD');
    if not exists (select 1 from pg_class where relname = v_name) then
        execute format(
            'create table %I partition of audit_log for values from (%L) to (%L)',
            v_name,
            p_day::text || ' 00:00:00+00',
            (p_day + 1)::text || ' 00:00:00+00'
        );
        -- Reads and writes go through audit_log; the partition itself is not open to anyone.
        execute format('revoke all on %I from public', v_name);
    end if;
    return v_name;
end;
$$;

comment on function audit_log_create_day_partition(date) is
    'Creates the daily partition of audit_log for a day unless a monthly partition covers it (SECURITY DEFINER)';

create or replace function audit_log_detach_day_partition(p_day date)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_name text;
    v_archived_name text;
begin
    if p_day is null or extract(year from p_day) < 2020 or extract(year from p_day) > 2100 then
        raise exception 'Invalid day: %', p_day using errcode = 'check_violation';
    end if;
    -- Same floor as the monthly function (V125): the current and the previous month stay attached.
    if p_day >= (date_trunc('month', now() at time zone 'UTC') - interval '1 month')::date then
        raise exception 'Partition % is too recent to detach', to_char(p_day, 'YYYY_MM_DD')
            using errcode = 'check_violation';
    end if;

    v_name := 'audit_log_' || to_char(p_day, 'YYYY_MM_DD');
    v_archived_name := 'audit_log_archived_' || to_char(p_day, 'YYYY_MM_DD');
    if not exists (select 1 from pg_class where relname = v_name) then
        raise exception 'Partition table % does not exist', v_name using errcode = 'undefined_table';
    end if;

    execute format('alter table audit_log detach partition %I', v_name);
    execute format('alter table %I rename to %I', v_name, v_archived_name);
    execute format('revoke all on %I from public', v_archived_name);
    return v_archived_name;
end;
$$;

comment on function audit_log_detach_day_partition(date) is
    'Retention of a daily audit_log partition: detach and rename to audit_log_archived_YYYY_MM_DD (SECURITY DEFINER)';

-- ---------- dropping a partition, only when a verified archive holds it ----------
create or replace function audit_log_drop_archived_partition(p_name text)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_parts text[];
    v_from date;
    v_to date;
    v_attached boolean;
begin
    v_parts := regexp_match(p_name, '^audit_log_(archived_)?([0-9]{4})_([0-9]{2})(?:_([0-9]{2}))?$');
    if v_parts is null then
        raise exception 'Not an audit partition: %', p_name using errcode = 'check_violation';
    end if;
    v_from := make_date(v_parts[2]::int, v_parts[3]::int, coalesce(v_parts[4]::int, 1));
    v_to := case when v_parts[4] is null then (v_from + interval '1 month')::date else v_from + 1 end;
    if v_to > (now() at time zone 'UTC')::date then
        raise exception 'Partition % is still open', p_name using errcode = 'check_violation';
    end if;
    if not exists (select 1
                   from audit_log_archive_partitions ap
                   join audit_log_archives a on a.id = ap.archive_id
                   where ap.partition_name = p_name and a.verified_at is not null and a.file_deleted_at is null) then
        -- An archive removed by its retention is no copy any more: the partition stays.
        raise exception 'Partition % has no verified archive', p_name using errcode = 'check_violation';
    end if;
    if not exists (select 1 from pg_class where relname = p_name and relkind = 'r') then
        raise exception 'Partition table % does not exist', p_name using errcode = 'undefined_table';
    end if;

    select exists (select 1
                   from pg_inherits i
                   join pg_class c on c.oid = i.inhrelid
                   join pg_class p on p.oid = i.inhparent
                   where c.relname = p_name and p.relname = 'audit_log')
    into v_attached;
    if v_attached then
        execute format('alter table audit_log detach partition %I', p_name);
    end if;
    execute format('drop table %I', p_name);
    update audit_log_archive_partitions set dropped_at = now() where partition_name = p_name;
end;
$$;

comment on function audit_log_drop_archived_partition(text) is
    'Drops a closed audit_log partition that a verified archive holds (SECURITY DEFINER)';

-- ---------- the empty future monthly partitions give way to daily ones ----------
do $$
declare
    r record;
    v_start date;
    v_empty boolean;
begin
    for r in
        select c.relname
        from pg_inherits i
        join pg_class c on c.oid = i.inhrelid
        join pg_class p on p.oid = i.inhparent
        where p.relname = 'audit_log' and c.relname ~ '^audit_log_[0-9]{4}_[0-9]{2}$'
    loop
        v_start := to_date(substr(r.relname, 11), 'YYYY_MM');
        if v_start > date_trunc('month', now() at time zone 'UTC')::date then
            execute format('select not exists (select 1 from %I)', r.relname) into v_empty;
            if v_empty then
                execute format('drop table %I', r.relname);
            end if;
        end if;
    end loop;
end
$$;

-- ---------- execution for the roles that write audit_log, as in V125 ----------
revoke execute on function audit_log_create_day_partition(date) from public;
revoke execute on function audit_log_detach_day_partition(date) from public;
revoke execute on function audit_log_drop_archived_partition(text) from public;

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
        execute format('grant execute on function audit_log_create_day_partition(date) to %I', r.rolname);
        execute format('grant execute on function audit_log_detach_day_partition(date) to %I', r.rolname);
        execute format('grant execute on function audit_log_drop_archived_partition(text) to %I', r.rolname);
    end loop;
end
$$;
