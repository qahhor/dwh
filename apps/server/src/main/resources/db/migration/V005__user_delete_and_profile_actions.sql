-- ============================================================================
-- V005: Действие удаления (анонимизации) пользователей (FR-USR-8)
-- ============================================================================

insert into md_form_actions (form_code, action, name) values
('iam.users', 'delete', 'Удаление (анонимизация) пользователя')
on conflict (form_code, action) do nothing;

-- Назначение права admin (полное покрытие каталога)
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'iam.users', 'delete'
from md_roles r
where r.pcode = 'admin'
on conflict do nothing;
