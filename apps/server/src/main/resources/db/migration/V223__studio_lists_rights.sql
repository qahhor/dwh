set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0041: reference lists are read by form publishers and kept by administrators; no grants to ordinary users.
insert into md_forms (code, module, name) values ('studio.lists', 'studio', 'Справочники') on conflict (code) do nothing;
insert into md_form_actions (form_code, action, name) values
('studio.lists', 'view', 'Просмотр'),
('studio.lists', 'manage', 'Ведение')
on conflict do nothing;
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'studio.lists', a.action
from md_roles r cross join (values ('view'), ('manage')) as a (action)
where r.pcode in ('chief_admin', 'admin')
on conflict do nothing;
