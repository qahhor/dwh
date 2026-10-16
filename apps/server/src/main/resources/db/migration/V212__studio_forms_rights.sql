set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0038: publishing is separate from settings; no automatic grants to managers or ordinary users.
insert into md_forms (code, module, name) values ('studio.forms', 'studio', 'Активация форм') on conflict (code) do nothing;
insert into md_form_actions (form_code, action, name) values ('studio.forms', 'publish', 'Активация') on conflict do nothing;
insert into md_role_permissions (role_id, form_code, action)
select id, 'studio.forms', 'publish' from md_roles where pcode in ('chief_admin', 'admin') on conflict do nothing;
insert into md_installed_modules (code, name, description, icon, route, is_system, status, sort_order) values
('studio', 'Созданные формы', 'Созданные формы', 'box', '', false, 'ACTIVE', 100)
on conflict (code) do nothing;
