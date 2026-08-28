-- ============================================================================
-- V004: действия формы rbac.assignments (ТЗ-04 разд. 4.4, FR-PERM-4/5/10)
-- Форма была в каталоге, но без действий — эндпоинты назначения ролей и
-- персональных прав не могли объявить право (обнаружено сквозным прогоном).
-- Expand-миграция: только добавления.
-- ============================================================================

insert into md_forms (code, module, name) values
('rbac.assignments', 'md', 'Назначение прав')
on conflict (code) do nothing;

insert into md_form_actions (form_code, action, name) values
('rbac.assignments', 'view',   'Просмотр назначений и эффективных прав'),
('rbac.assignments', 'assign', 'Назначение ролей и персональных прав')
on conflict (form_code, action) do nothing;

-- admin: полное покрытие каталога (I-P4)
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode = 'admin' and fa.form_code = 'rbac.assignments'
on conflict do nothing;

-- auditor: только просмотр (роль без единой мутации)
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'rbac.assignments', 'view'
from md_roles r where r.pcode = 'auditor'
on conflict do nothing;
