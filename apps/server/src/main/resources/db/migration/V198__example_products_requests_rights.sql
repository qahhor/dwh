set lock_timeout = '2s';
set statement_timeout = '60s';
-- Plan 10/10, item 6.6: the rights of the reference products and requests (area example, ADR-0028) - an action per
-- transition of the requests' process, import of the products - and the grants to the system roles. The module row
-- is V182's (shipped switched off). Seed data only: the tables are V197's.
insert into md_forms (code, module, name) values
('example.products', 'example', 'Товары (эталон справочника)'),
('example.requests', 'example', 'Заявки (эталон документа со статусами)')
on conflict (code) do nothing;

insert into md_form_actions (form_code, action, name) values
('example.products', 'view', 'Просмотр товаров'),
('example.products', 'create', 'Создание товара'),
('example.products', 'update', 'Изменение товара'),
('example.products', 'delete', 'Удаление товара'),
('example.products', 'import', 'Импорт товаров'),
('example.requests', 'view', 'Просмотр заявок'),
('example.requests', 'create', 'Создание заявки'),
('example.requests', 'update', 'Изменение заявки'),
('example.requests', 'delete', 'Удаление заявки'),
('example.requests', 'submit', 'Отправка заявки'),
('example.requests', 'recall', 'Отзыв заявки'),
('example.requests', 'approve', 'Утверждение заявки'),
('example.requests', 'reject', 'Отклонение заявки')
on conflict (form_code, action) do nothing;

-- Administrators and managers get every action
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode in ('chief_admin', 'admin', 'manager') and fa.form_code in ('example.products', 'example.requests')
on conflict do nothing;

-- The user and auditor roles only view; analyst (V110) gets a module's rights from an administrator
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode in ('user', 'auditor') and fa.form_code in ('example.products', 'example.requests')
    and fa.action = 'view'
on conflict do nothing;
