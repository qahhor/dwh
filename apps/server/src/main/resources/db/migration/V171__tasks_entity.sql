set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: tasks move to the general entity runtime (ADR-0032, 8, step 4). A task keeps the code of its status and of
--         its type (ENUM over the reference entities ms.task_statuses and ms.task_types) instead of the status id and
--         an attribute, and its responsible person in a column of its own. No installation holds a task yet
--         (AGENTS.md): existing rows start in the status new and the type task, without a responsible person.
-- approved_by: product owner (plan 10/10, item 5.6)
-- The codes are checked by the runtime against the references, which archive their items instead of deleting them
-- while tasks use them; a foreign key cannot follow a code that is unique only among the items in use. The published
-- views name the status by its code (ADR-0026).
drop view ms_task_pub_tasks;
drop view ms_task_pub_statuses;
alter table ms_tasks drop column status_id;
alter table ms_tasks
    add column status_code text not null default 'new',
    add column type_code text not null default 'task',
    add column responsible_id bigint constraint ms_tasks_fk_responsible references md_users (id);
alter table ms_tasks alter column description_markdown drop not null;
alter table ms_tasks
    add constraint ms_tasks_ck_status_code check (status_code ~ '^[a-z][a-z0-9_]{0,63}$') not valid,
    add constraint ms_tasks_ck_type_code check (type_code ~ '^[a-z][a-z0-9_]{0,63}$') not valid;

-- ADR-0032, 4.1: executors and observers are several references kept in the participants table, in their order.
alter table ms_task_members add column position integer not null default 0;

create view ms_task_pub_statuses as
select id, code, name, is_terminal
from ms_task_statuses;
create view ms_task_pub_tasks as
select id, project_id, status_code, title, description_markdown, priority, reporter_id, created_by, end_time,
       resolved_time, created_at, modified_at
from ms_tasks;
create trigger ms_task_pub_statuses_read_only instead of insert or update or delete on ms_task_pub_statuses
    for each row execute function pub_view_refuse_write();
create trigger ms_task_pub_tasks_read_only instead of insert or update or delete on ms_task_pub_tasks
    for each row execute function pub_view_refuse_write();
