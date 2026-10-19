set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0047: the rights of solution packages. "export" downloads a package of chosen forms with what they use, "import"
-- checks and applies a package or a template; only administrators get them. Applying still requires the rights of
-- each kind of item the package creates, and a package never grants a right by itself.
insert into md_forms (code, module, name) values ('studio.packages', 'studio', 'Пакеты решений') on conflict (code) do nothing;
insert into md_form_actions (form_code, action, name) values
('studio.packages', 'export', 'Выгрузка'),
('studio.packages', 'import', 'Загрузка')
on conflict do nothing;
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'studio.packages', a.action
from md_roles r cross join (values ('export'), ('import')) as a (action)
where r.pcode in ('chief_admin', 'admin')
on conflict do nothing;
