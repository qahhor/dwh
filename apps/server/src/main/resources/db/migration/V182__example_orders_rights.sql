set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0032, 9.4 and 19 (plan 10/10, item 5.7): the right of the reference orders (area example, ADR-0028) with an
-- action per transition of their process, the grants to the system roles, and the module in the registry - shipped and
-- switched off (question 3 of ADR-0032, 19: the proposed default, taken as an assumption); an administrator switches it
-- on in the module registry. Seed data only: the tables are V181's.
insert into md_forms (code, module, name) values
('example.orders', 'example', 'Заказы (эталон документа)')
on conflict (code) do nothing;

insert into md_form_actions (form_code, action, name) values
('example.orders', 'view', 'Просмотр заказов'),
('example.orders', 'create', 'Создание заказа'),
('example.orders', 'update', 'Изменение заказа'),
('example.orders', 'post', 'Проведение заказа'),
('example.orders', 'unpost', 'Отмена проведения заказа'),
('example.orders', 'cancel', 'Отмена заказа')
on conflict (form_code, action) do nothing;

-- Administrators and managers get every action
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode in ('chief_admin', 'admin', 'manager') and fa.form_code = 'example.orders'
on conflict do nothing;

-- The user and auditor roles only view; analyst (V110) gets a module's rights from an administrator
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode in ('user', 'auditor') and fa.form_code = 'example.orders' and fa.action = 'view'
on conflict do nothing;

insert into md_installed_modules (code, name, description, version, icon, route, is_system, status, sort_order) values
('example', 'Эталон: документ со строками', 'Заказы со строками и статусами на общем экране сущности', '1.0.0',
 'receipt_long', '/e/example.orders', false, 'DISABLED', 110)
on conflict (code) do nothing;
