set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: projects move to the general entity runtime (ADR-0032, 8, step 3). The paused state 'P' is the archive of
--         the ARCHIVE capability now, so the state column goes (no client installation holds a project yet, AGENTS.md);
--         the unique project name ignores archived projects; the published view names the archive instead of the state.
-- approved_by: product owner (plan 10/10, item 5.6)
-- ADR-0032, 14.1: the editor and the time of the last change, who archived the project and when; the columns of a
-- person have their indexes (ADR-0020).
drop view ms_task_pub_projects;
alter table ms_task_projects drop constraint ms_task_projects_name_key;
alter table ms_task_projects drop column state;
alter table ms_task_projects
    add column modified_by bigint constraint ms_task_projects_fk_modified_by references md_users (id),
    add column modified_at timestamptz not null default clock_timestamp(),
    add column archived_at timestamptz,
    add column archived_by bigint constraint ms_task_projects_fk_archived_by references md_users (id);
alter table ms_task_projects
    add constraint ms_task_projects_ck_description check (char_length(description) <= 10000) not valid;
create unique index ms_task_projects_name_uq on ms_task_projects (name) where archived_at is null;
create index ms_task_projects_modified_by_idx on ms_task_projects (modified_by);
create index ms_task_projects_archived_by_idx on ms_task_projects (archived_by);

-- ADR-0026: the analytics and the search read projects through the published view; an archived project is the paused
-- one they skip.
create view ms_task_pub_projects as
select id, name, description, archived_at is not null as archived
from ms_task_projects;
create trigger ms_task_pub_projects_read_only instead of insert or update or delete on ms_task_pub_projects
    for each row execute function pub_view_refuse_write();
