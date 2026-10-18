set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0044: administrators read and keep report datasets; readers of reports need no right on the dataset itself, only
-- the view right of its source entity, and see it through their own scope and field rights.
insert into md_forms (code, module, name) values ('md.report_datasets', 'md', 'Наборы данных отчётов') on conflict (code) do nothing;
insert into md_form_actions (form_code, action, name) values
('md.report_datasets', 'view', 'Просмотр'),
('md.report_datasets', 'manage', 'Ведение')
on conflict do nothing;
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'md.report_datasets', a.action
from md_roles r cross join (values ('view'), ('manage')) as a (action)
where r.pcode in ('chief_admin', 'admin')
on conflict do nothing;
