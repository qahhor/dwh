set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: the task types and statuses move to the general entity runtime (ADR-0032, 8 and 14.2). The order column is
--         renamed to the entity convention, the status's system code becomes its code (a status without one gets a
--         code from its id) and the unique codes ignore archived rows; no row is removed.
-- approved_by: product owner (plan 10/10, item 5.6)
-- ADR-0032, 14.1: every entity table has its author and editor, the time of the last change, the custom field values
-- and, for the ARCHIVE capability, who archived the row and when. The columns of a person have their indexes
-- (ADR-0020). New checks are not validated against rows written before them.

alter table ms_task_types rename column order_no to sort_order;
alter table ms_task_types drop constraint ms_task_types_code_key;
alter table ms_task_types
    add column attributes jsonb not null default '{}'::jsonb,
    add column created_by bigint constraint ms_task_types_fk_created_by references md_users (id),
    add column modified_by bigint constraint ms_task_types_fk_modified_by references md_users (id),
    add column modified_at timestamptz not null default clock_timestamp(),
    add column archived_at timestamptz,
    add column archived_by bigint constraint ms_task_types_fk_archived_by references md_users (id);
alter table ms_task_types
    add constraint ms_task_types_ck_attributes check (jsonb_typeof(attributes) = 'object'),
    add constraint ms_task_types_ck_code check (code ~ '^[a-z][a-z0-9_]{0,63}$') not valid,
    add constraint ms_task_types_ck_name check (char_length(name) between 1 and 255) not valid,
    add constraint ms_task_types_ck_color check (color ~ '^#[0-9a-fA-F]{6}$') not valid;
create unique index ms_task_types_code_uq on ms_task_types (code) where archived_at is null;
create index ms_task_types_created_by_idx on ms_task_types (created_by);
create index ms_task_types_modified_by_idx on ms_task_types (modified_by);
create index ms_task_types_archived_by_idx on ms_task_types (archived_by);

alter table ms_task_statuses rename column order_no to sort_order;
alter table ms_task_statuses rename column pcode to code;
alter table ms_task_statuses drop constraint ms_task_statuses_pcode_key;
alter table ms_task_statuses
    add column is_system boolean not null default false,
    add column attributes jsonb not null default '{}'::jsonb,
    add column created_by bigint constraint ms_task_statuses_fk_created_by references md_users (id),
    add column modified_by bigint constraint ms_task_statuses_fk_modified_by references md_users (id),
    add column created_at timestamptz not null default clock_timestamp(),
    add column modified_at timestamptz not null default clock_timestamp(),
    add column archived_at timestamptz,
    add column archived_by bigint constraint ms_task_statuses_fk_archived_by references md_users (id);
-- The four statuses the product ships with are the system ones: they had the system code.
update ms_task_statuses set is_system = true where code is not null;
update ms_task_statuses set code = 'status_' || id where code is null;
alter table ms_task_statuses alter column code set not null;
alter table ms_task_statuses
    add constraint ms_task_statuses_ck_attributes check (jsonb_typeof(attributes) = 'object'),
    add constraint ms_task_statuses_ck_code check (code ~ '^[a-z][a-z0-9_]{0,63}$') not valid,
    add constraint ms_task_statuses_ck_name check (char_length(name) between 1 and 255) not valid,
    add constraint ms_task_statuses_ck_color check (color ~ '^#[0-9a-fA-F]{6}$') not valid;
create unique index ms_task_statuses_code_uq on ms_task_statuses (code) where archived_at is null;
create index ms_task_statuses_created_by_idx on ms_task_statuses (created_by);
create index ms_task_statuses_modified_by_idx on ms_task_statuses (modified_by);
create index ms_task_statuses_archived_by_idx on ms_task_statuses (archived_by);
