set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 3.6 (ADR-0024): a change of a record names the revision it was made from (If-Match), and the
-- second of two concurrent saves gets 409 instead of overwriting the first. Every save of these records raises the
-- revision by one. A constant default is only recorded in the catalog: adding the column rewrites no table.
alter table ms_notes add column revision bigint not null default 1;
alter table ms_task_projects add column revision bigint not null default 1;
alter table md_users add column revision bigint not null default 1;
alter table md_roles add column revision bigint not null default 1;
alter table md_custom_fields add column revision bigint not null default 1;
alter table md_org_units add column revision bigint not null default 1;
alter table md_navigation_items add column revision bigint not null default 1;
alter table ms_task_statuses add column revision bigint not null default 1;
alter table ms_task_types add column revision bigint not null default 1;
alter table kwh_subscriptions add column revision bigint not null default 1;
