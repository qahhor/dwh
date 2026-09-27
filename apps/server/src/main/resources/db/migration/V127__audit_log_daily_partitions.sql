set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: audit_log is archived weekly or at 100 MB (decision of 2026-09-27), so its partitions become daily. The
--         empty future monthly partitions that V011 created up to 2027-12 would overlap the daily ones and are
--         dropped; a monthly partition that already holds a row stays; daily partitions for the next 31 days are
--         created at once. Current and past months stay monthly and are archived whole. A partition is dropped only
--         by audit_log_drop_archived_partition: when a verified archive whose file still exists holds exactly its rows.
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

-- The name recorded is the partition's name when it was archived; retention may rename it to audit_log_archived_*.
create table audit_log_archive_partitions (
    archive_id bigint not null,
    partition_name text not null,
    row_count bigint not null,
    dropped_at timestamptz,
    constraint audit_log_archive_partitions_pkey primary key (archive_id, partition_name),
    constraint audit_log_archive_partitions_fk_archive foreign key (archive_id)
        references audit_log_archives (id) on delete cascade
);
create index audit_log_archive_partitions_name_idx on audit_log_archive_partitions (partition_name);

-- One run at a time across instances: a lease taken by an upsert, released at the end of the run.
create table audit_log_archive_lease (
    id int primary key,
    holder text not null,
    expires_at timestamptz not null,
    constraint audit_log_archive_lease_ck_single check (id = 1)
);

comment on table audit_log_archives is
    'Archive files of audit_log partitions (gzip JSON lines), in local storage or S3. verified_at: read back and '
    'matched by SHA-256 and row count; file_deleted_at: removed by the archive retention. A verified record is '
    'permanent: it is the trace of every partition that left the database.';

-- ---------- the trace of an archive cannot be rewritten ----------
create or replace function audit_log_archives_guard() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'INSERT' then
        if new.verified_at is not null or new.file_deleted_at is not null then
            raise exception 'An archive is recorded unverified and verified by reading it back'
                using errcode = 'restrict_violation';
        end if;
        return new;
    end if;
    if tg_op = 'DELETE' then
        if old.verified_at is not null then
            raise exception 'A verified archive record is permanent' using errcode = 'restrict_violation';
        end if;
        return old;
    end if;
    -- UPDATE: verified_at and file_deleted_at are set once; nothing else changes.
    if (new.id, new.file_key, new.storage, new.period_from, new.period_to, new.row_count, new.byte_size, new.sha256,
        new.created_at)
       is distinct from
       (old.id, old.file_key, old.storage, old.period_from, old.period_to, old.row_count, old.byte_size, old.sha256,
        old.created_at)
       or (old.verified_at is not null and new.verified_at is distinct from old.verified_at)
       or (old.file_deleted_at is not null and new.file_deleted_at is distinct from old.file_deleted_at)
       or (new.file_deleted_at is not null and new.verified_at is null) then
        raise exception 'An archive record changes only by being verified or by its file being removed'
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger audit_log_archives_guard
    before insert or update or delete on audit_log_archives
    for each row execute function audit_log_archives_guard();

create or replace function audit_log_archive_partitions_guard() returns trigger
    language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        -- Removed with an unverified archive (the cascade), never from a verified one.
        if exists (select 1 from audit_log_archives a where a.id = old.archive_id and a.verified_at is not null) then
            raise exception 'The partitions of a verified archive are permanent' using errcode = 'restrict_violation';
        end if;
        return old;
    end if;
    if tg_op = 'UPDATE' and ((new.archive_id, new.partition_name, new.row_count)
                             is distinct from (old.archive_id, old.partition_name, old.row_count)
                             or old.dropped_at is not null) then
        raise exception 'A partition record changes only by being dropped' using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger audit_log_archive_partitions_guard
    before update or delete on audit_log_archive_partitions
    for each row execute function audit_log_archive_partitions_guard();

-- ---------- daily partitions ----------
create or replace function audit_log_create_day_partition(p_day date)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
set lock_timeout = '5s'
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
    if to_regclass(v_month_name) is not null then
        return v_month_name;
    end if;

    v_name := 'audit_log_' || to_char(p_day, 'YYYY_MM_DD');
    if to_regclass(v_name) is null then
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
set lock_timeout = '5s'
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
    if to_regclass(v_name) is null then
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

-- ---------- dropping a partition: a verified archive, its file still there, exactly its rows ----------
create or replace function audit_log_drop_archived_partition(p_name text)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
set lock_timeout = '5s'
as $$
declare
    v_parts text[];
    v_recorded_name text;
    v_from date;
    v_to date;
    v_archived_rows bigint;
    v_rows bigint;
    v_attached boolean;
begin
    v_parts := regexp_match(p_name, '^audit_log_(archived_)?([0-9]{4})_([0-9]{2})(?:_([0-9]{2}))?$');
    if v_parts is null then
        raise exception 'Not an audit partition: %', p_name using errcode = 'check_violation';
    end if;
    -- Retention renames audit_log_X to audit_log_archived_X; the archive recorded the first name.
    v_recorded_name := 'audit_log_' || v_parts[2] || '_' || v_parts[3] || coalesce('_' || v_parts[4], '');
    v_from := make_date(v_parts[2]::int, v_parts[3]::int, coalesce(v_parts[4]::int, 1));
    v_to := case when v_parts[4] is null then (v_from + interval '1 month')::date else v_from + 1 end;
    -- A full day after the period ends: a transaction started before midnight may still commit a row into it.
    if v_to + 1 > (now() at time zone 'UTC')::date then
        raise exception 'Partition % is still open', p_name using errcode = 'check_violation';
    end if;

    select ap.row_count into v_archived_rows
    from audit_log_archive_partitions ap
    join audit_log_archives a on a.id = ap.archive_id
    where ap.partition_name = v_recorded_name and ap.dropped_at is null
      and a.verified_at is not null and a.file_deleted_at is null
    order by a.created_at desc
    limit 1;
    if v_archived_rows is null then
        -- An archive removed by its retention is no copy any more: the partition stays.
        raise exception 'Partition % has no verified archive', p_name using errcode = 'check_violation';
    end if;
    if to_regclass(p_name) is null then
        raise exception 'Partition table % does not exist', p_name using errcode = 'undefined_table';
    end if;

    -- No row may arrive between the count and the drop, and every row must be in the archive.
    execute format('lock table %I in access exclusive mode', p_name);
    execute format('select count(*) from %I', p_name) into v_rows;
    if v_rows <> v_archived_rows then
        raise exception 'Partition % has % rows, its archive % : the rows added later are in no copy',
            p_name, v_rows, v_archived_rows using errcode = 'check_violation';
    end if;

    select exists (select 1 from pg_inherits i
                   where i.inhrelid = to_regclass(p_name) and i.inhparent = to_regclass('audit_log'))
    into v_attached;
    if v_attached then
        execute format('alter table audit_log detach partition %I', p_name);
    end if;
    execute format('drop table %I', p_name);
    update audit_log_archive_partitions set dropped_at = now()
    where partition_name = v_recorded_name and dropped_at is null;
end;
$$;

comment on function audit_log_drop_archived_partition(text) is
    'Drops a closed audit_log partition whose rows a verified, unexpired archive holds (SECURITY DEFINER)';

-- ---------- the empty future monthly partitions give way to daily ones ----------
do $$
declare
    r record;
    v_start date;
    v_empty boolean;
    v_day date;
begin
    for r in
        select c.relname
        from pg_inherits i
        join pg_class c on c.oid = i.inhrelid
        where i.inhparent = to_regclass('audit_log') and c.relname ~ '^audit_log_[0-9]{4}_[0-9]{2}$'
    loop
        v_start := to_date(substr(r.relname, 11), 'YYYY_MM');
        if v_start > date_trunc('month', now() at time zone 'UTC')::date then
            execute format('select not exists (select 1 from %I)', r.relname) into v_empty;
            if v_empty then
                execute format('drop table %I', r.relname);
            end if;
        end if;
    end loop;

    -- The runway V011 gave is kept: 31 days ahead exist before the new server version first starts.
    v_day := (now() at time zone 'UTC')::date;
    while v_day <= (now() at time zone 'UTC')::date + 31 loop
        perform audit_log_create_day_partition(v_day);
        v_day := v_day + 1;
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
