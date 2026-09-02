-- ============================================================================
-- V003: Каталог форм — доукомплектация + матрица прав системных ролей
-- Основание: ТЗ-01 разд. 4.4.1 (утверждена CEO 2026-08-27), FR-PERM-12.
-- Expand-миграция: только добавления, on conflict do nothing.
-- Состав проверяется автотестом RbacSystemRolesIntegrationTest (R6).
-- ============================================================================

-- 1. Формы, которые использовали контроллеры без регистрации в каталоге
insert into md_forms (code, module, name) values
('audit.log',         'audit',  'Аудит и security-журнал'),
('platform.webhooks', 'kwh',    'Исходящие вебхуки'),
('platform.files',    'mf',     'Файлы'),
('platform.search',   'search', 'Поиск')
on conflict (code) do nothing;

insert into md_form_actions (form_code, action, name) values
('audit.log',         'view',   'Просмотр аудита'),
('platform.webhooks', 'view',   'Просмотр подписок'),
('platform.webhooks', 'manage', 'Управление подписками'),
('platform.files',    'view',   'Просмотр и скачивание'),
('platform.files',    'upload', 'Загрузка файлов'),
('platform.search',   'view',   'Глобальный поиск'),
('notify.preferences', 'view',   'Просмотр настроек уведомлений'),
('notify.preferences', 'update', 'Изменение настроек уведомлений')
on conflict (form_code, action) do nothing;

-- 2. admin: добор новых пар (полное покрытие каталога — правило I-P4)
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode = 'admin'
on conflict do nothing;

-- 3. auditor: ТОЛЬКО просмотр — все view-действия каталога, ни одной мутации
--    (определение роли, ТЗ-01 разд. 4.4.1; проверяется автотестом)
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode = 'auditor' and fa.action = 'view'
on conflict do nothing;

-- 4. manager: профиль полностью; пользователи — просмотр; задачник — управление;
--    файлы/поиск/уведомления/объявления
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
join md_form_actions fa on (fa.form_code, fa.action) in (
    ('iam.profile', 'view'), ('iam.profile', 'update'),
    ('iam.profile', 'manage_tokens'), ('iam.profile', 'manage_channels'),
    ('iam.users', 'view'),
    ('tasks.projects', 'view'), ('tasks.projects', 'create'), ('tasks.projects', 'update'),
    ('tasks.items', 'view'), ('tasks.items', 'create'), ('tasks.items', 'update'),
    ('tasks.comments', 'view'), ('tasks.comments', 'create'),
    ('platform.files', 'view'), ('platform.files', 'upload'),
    ('platform.search', 'view'),
    ('notify.inbox', 'view'),
    ('notify.preferences', 'view'), ('notify.preferences', 'update'),
    ('platform.announcements', 'view')
)
where r.pcode = 'manager'
on conflict do nothing;

-- 5. user: профиль; участие в задачах; файлы/поиск/уведомления/объявления
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
join md_form_actions fa on (fa.form_code, fa.action) in (
    ('iam.profile', 'view'), ('iam.profile', 'update'),
    ('iam.profile', 'manage_tokens'), ('iam.profile', 'manage_channels'),
    ('tasks.projects', 'view'),
    ('tasks.items', 'view'), ('tasks.items', 'create'), ('tasks.items', 'update'),
    ('tasks.comments', 'view'), ('tasks.comments', 'create'),
    ('platform.files', 'view'), ('platform.files', 'upload'),
    ('platform.search', 'view'),
    ('notify.inbox', 'view'),
    ('notify.preferences', 'view'), ('notify.preferences', 'update'),
    ('platform.announcements', 'view')
)
where r.pcode = 'user'
on conflict do nothing;
