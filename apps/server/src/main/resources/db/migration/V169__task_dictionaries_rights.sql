set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032, 6.10 and 8 (plan 10/10, item 5.6): the task types and statuses are entities with rights of their own,
-- tasks.types and tasks.statuses, instead of the task right. Whoever could view tasks views them; whoever could change
-- tasks keeps managing them: create, change, reorder, archive and delete. Seed data only: the tables are V168's.
insert into md_forms (code, module, name) values
('tasks.types', 'ms.task', 'Типы задач'),
('tasks.statuses', 'ms.task', 'Статусы задач')
on conflict (code) do nothing;

insert into md_form_actions (form_code, action, name) values
('tasks.types', 'view', 'Просмотр типов задач'),
('tasks.types', 'create', 'Создание типа задачи'),
('tasks.types', 'update', 'Изменение и порядок типов задач'),
('tasks.types', 'delete', 'Архив и удаление типа задачи'),
('tasks.statuses', 'view', 'Просмотр статусов задач'),
('tasks.statuses', 'create', 'Создание статуса задачи'),
('tasks.statuses', 'update', 'Изменение и порядок статусов задач'),
('tasks.statuses', 'delete', 'Архив и удаление статуса задачи')
on conflict (form_code, action) do nothing;

insert into md_role_permissions (role_id, form_code, action)
select rp.role_id, fa.form_code, fa.action
from md_role_permissions rp
cross join md_form_actions fa
where rp.form_code = 'tasks.items' and rp.action = 'view'
  and fa.form_code in ('tasks.types', 'tasks.statuses') and fa.action = 'view'
on conflict do nothing;

insert into md_role_permissions (role_id, form_code, action)
select rp.role_id, fa.form_code, fa.action
from md_role_permissions rp
cross join md_form_actions fa
where rp.form_code = 'tasks.items' and rp.action = 'update'
  and fa.form_code in ('tasks.types', 'tasks.statuses') and fa.action in ('create', 'update', 'delete')
on conflict do nothing;
