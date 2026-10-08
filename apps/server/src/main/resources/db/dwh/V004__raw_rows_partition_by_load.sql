set lock_timeout = '2s';
set statement_timeout = '60s';
-- pg-dwh, plan 10/10, item 7.8: raw.rows becomes a table partitioned by load (LIST on load_id, one partition per
-- load, ADR-0030 section 7). The writer creates the partition of its load next to the table, fills it with COPY and
-- attaches it in the same transaction; the cleanup of a failed load detaches and drops its partition instead of
-- deleting rows, so a million-row load leaves no dead tuples and takes no vacuum.
-- destructive: approved
-- reason: no client installations exist before the final release, so raw holds no rows to keep; the table is recreated
-- reason: as partitioned instead of being converted (a plain table cannot be turned into a partitioned one in place)
-- approved_by: product owner (no client installations before the final release, decision 2026-10-01; plan item 7.8)

drop table raw.rows;

create table raw.rows (
    load_id         bigint       not null,
    source_file_id  uuid,
    row_no          bigint       not null,
    sheet           text,
    source_row_no   integer,
    fields          jsonb        not null,
    loaded_at       timestamptz  not null default now(),
    primary key (load_id, row_no)
) partition by list (load_id);

comment on table raw.rows is
    'Rows as read from the file, untyped; one partition raw.rows_<load_id> per load (fnd_loads.id in OLTP)';
comment on column raw.rows.source_file_id is 'mf_files.id of the framework in OLTP; no cross-database foreign key';

-- The cross-database check reads the distinct files of raw; rows without a file take no part in it.
create index raw_rows_source_file_idx on raw.rows (source_file_id) where source_file_id is not null;

-- Raw is immutable: a row is never updated or deleted, the rows of a load leave only with its partition. The row
-- trigger is cloned to every partition when it is attached; DROP and DETACH do not fire it.
create or replace function raw.rows_immutable() returns trigger
    language plpgsql as $$
begin
    raise exception 'raw_rows_immutable: raw rows leave only with the partition of their load'
        using errcode = 'P0001';
end
$$;

create trigger raw_rows_immutable
    before update or delete on raw.rows
    for each row execute function raw.rows_immutable();
