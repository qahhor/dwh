set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0026: published read views. A module publishes the columns other modules may read as a view named
-- <module prefix>_pub_<name>; readers join the view instead of the base table. Only the columns readers need are
-- published, never a password, a hash, a secret or a token. The views are simple (one table, no aggregate), so
-- PostgreSQL inlines them into the reader's query and uses the indexes of the base table.
-- Writes through a view are refused: a change goes through the owning module's service.

create or replace function pub_view_refuse_write()
returns trigger
language plpgsql
as $$
begin
    raise exception 'published read view % is read-only (ADR-0026)', tg_table_name
        using errcode = 'insufficient_privilege';
end
$$;

create view md_pub_users as
select id, login, name, email, phone, state, auth_version
from md_users;

create view ms_task_pub_tasks as
select id, project_id, status_id, title, description_markdown, priority, reporter_id, created_by,
       end_time, resolved_time, created_at, modified_at
from ms_tasks;

create view ms_task_pub_projects as
select id, name, description, state
from ms_task_projects;

create view ms_task_pub_statuses as
select id, name, is_terminal
from ms_task_statuses;

create view ms_task_pub_members as
select task_id, user_id, involve_kind
from ms_task_members;

create view ms_note_pub_notes as
select id, title, content_md, is_pinned
from ms_notes;

create view mf_pub_files as
select id, original_name, size_bytes, mime_type, created_at, created_by
from mf_files;

create trigger md_pub_users_read_only instead of insert or update or delete on md_pub_users
    for each row execute function pub_view_refuse_write();
create trigger ms_task_pub_tasks_read_only instead of insert or update or delete on ms_task_pub_tasks
    for each row execute function pub_view_refuse_write();
create trigger ms_task_pub_projects_read_only instead of insert or update or delete on ms_task_pub_projects
    for each row execute function pub_view_refuse_write();
create trigger ms_task_pub_statuses_read_only instead of insert or update or delete on ms_task_pub_statuses
    for each row execute function pub_view_refuse_write();
create trigger ms_task_pub_members_read_only instead of insert or update or delete on ms_task_pub_members
    for each row execute function pub_view_refuse_write();
create trigger ms_note_pub_notes_read_only instead of insert or update or delete on ms_note_pub_notes
    for each row execute function pub_view_refuse_write();
create trigger mf_pub_files_read_only instead of insert or update or delete on mf_pub_files
    for each row execute function pub_view_refuse_write();
