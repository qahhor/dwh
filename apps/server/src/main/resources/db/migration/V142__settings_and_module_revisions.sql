set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 3.6 (ADR-0024): the last shared records saved without a revision get one. A module registration
-- is a row of its own; a constant default is only recorded in the catalog, so adding the column rewrites no table.
alter table md_installed_modules add column revision bigint not null default 1;

-- The system settings are one row of md_settings per key, so the set has no revision of its own: this row holds it.
-- A missing row is revision 1; the first save inserts it with revision 2, every later save raises it by one.
create table md_settings_revision (
    scope text not null,
    revision bigint not null default 1,
    modified_at timestamptz not null default clock_timestamp(),
    constraint md_settings_revision_pkey primary key (scope),
    constraint md_settings_revision_ck_scope check (scope = 'system')
);
