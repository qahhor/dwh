set lock_timeout = '2s';
set statement_timeout = '60s';
-- pg-dwh, ADR-0048: normalized mart rows of applied warehouse loads for the report builder (Constructor Stage 3,
-- step 3.3). Numeric cells are stored converted to the format's base unit at the load's period_from; date and key
-- cells are stored normalized; rejected rows are excluded.

create table mart.rows (
    id              bigint generated always as identity primary key,
    source_code     text         not null,
    load_id         bigint       not null,
    period_from     date         not null,
    period_to       date         not null,
    sheet           text         not null,
    row_no          bigint       not null,
    fields          jsonb        not null,
    built_at        timestamptz  not null default now(),
    constraint rows_uk_load_row unique (load_id, row_no)
);

create index rows_source_period_idx on mart.rows (source_code, period_from, load_id);
create index rows_load_idx on mart.rows (load_id);

create view public.mart_rows as
    select id,
           source_code,
           load_id,
           period_from,
           period_to,
           sheet,
           row_no,
           fields,
           built_at
      from mart.rows;

comment on table mart.rows is
    'Normalized rows of applied loads for analytical reports (ADR-0048); filtered by source_code and active load_id';
