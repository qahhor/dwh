set lock_timeout = '2s';
set statement_timeout = '60s';

-- ADR-0026: published view for project members so search and analytics can read project participants
create view ms_task_pub_project_members as
select project_id, user_id, access_kind
from ms_task_project_members;

create trigger ms_task_pub_project_members_read_only instead of insert or update or delete on ms_task_pub_project_members
    for each row execute function pub_view_refuse_write();
