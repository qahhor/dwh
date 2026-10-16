set lock_timeout = '2s';
set statement_timeout = '60s';
-- ADR-0039: assistance grants no publication or record access.
insert into md_form_actions (form_code, action, name)
values ('studio.forms', 'assist', 'Подготовка черновика с помощником') on conflict do nothing;
insert into md_role_permissions (role_id, form_code, action)
select id, 'studio.forms', 'assist' from md_roles where pcode in ('chief_admin', 'admin') on conflict do nothing;
