-- ============================================================================
-- DWH Platform - Core Instance Initial Seed Data V002
-- ============================================================================

-- Экземплярные данные (instance_info, первый администратор) в миграциях ЗАПРЕЩЕНЫ:
-- они создаются при инициализации экземпляра из конфигурации (InstanceBootstrap,
-- FR-INST-1; AUDIT-03 C-1/C-2). Здесь — только справочники, одинаковые для всех.

-- 2. Core Forms
insert into md_forms (code, module, name) values
('iam.profile', 'md', 'Мой профиль'),
('iam.users', 'md', 'Пользователи'),
('rbac.roles', 'md', 'Роли и права'),
('rbac.assignments', 'md', 'Назначение прав'),
('md.custom_fields', 'md', 'Динамические поля'),
('platform.settings', 'md', 'Настройки платформы'),
('tasks.projects', 'ms.task', 'Проекты'),
('tasks.items', 'ms.task', 'Задачи'),
('tasks.comments', 'ms.task', 'Комментарии к задачам'),
('notify.inbox', 'ms.notify', 'Входящие оповещения'),
('notify.preferences', 'ms.notify', 'Настройки уведомлений'),
('platform.announcements', 'ms.notify', 'Объявления')
on conflict (code) do nothing;

-- 3. Core Form Actions
insert into md_form_actions (form_code, action, name) values
('iam.profile', 'view', 'Просмотр профиля'),
('iam.profile', 'update', 'Изменение данных'),
('iam.profile', 'manage_tokens', 'Управление токенами'),
('iam.profile', 'manage_channels', 'Управление каналами'),
('iam.users', 'view', 'Просмотр списка'),
('iam.users', 'create', 'Создание пользователя'),
('iam.users', 'update', 'Редактирование пользователя'),
('iam.users', 'block', 'Блокировка пользователя'),
('iam.users', 'unblock', 'Разблокировка пользователя'),
('rbac.roles', 'view', 'Просмотр ролей'),
('rbac.roles', 'create', 'Создание роли'),
('rbac.roles', 'update', 'Редактирование роли'),
('rbac.roles', 'delete', 'Удаление роли'),
('rbac.roles', 'grant', 'Настройка матрицы прав'),
('md.custom_fields', 'view', 'Просмотр полей'),
('md.custom_fields', 'create', 'Создание поля'),
('md.custom_fields', 'update', 'Редактирование поля'),
('md.custom_fields', 'delete', 'Удаление поля'),
('platform.settings', 'view', 'Просмотр настроек'),
('platform.settings', 'update', 'Изменение настроек'),
('tasks.projects', 'view', 'Просмотр проектов'),
('tasks.projects', 'create', 'Создание проекта'),
('tasks.projects', 'update', 'Редактирование проекта'),
('tasks.items', 'view', 'Просмотр задач'),
('tasks.items', 'create', 'Создание задачи'),
('tasks.items', 'update', 'Редактирование задачи'),
('tasks.comments', 'view', 'Просмотр комментариев'),
('tasks.comments', 'create', 'Добавление комментария'),
('notify.inbox', 'view', 'Просмотр оповещений'),
('platform.announcements', 'view', 'Просмотр объявлений')
on conflict (form_code, action) do nothing;

-- 4. Default Roles
insert into md_roles (name, pcode, state, order_no, created_at, modified_at) values
('Администратор', 'admin', 'A', 10, now(), now()),
('Менеджер', 'manager', 'A', 20, now(), now()),
('Аудитор', 'auditor', 'A', 30, now(), now()),
('Пользователь', 'user', 'A', 40, now(), now())
on conflict (pcode) do nothing;

-- 5. Grant all permissions to admin role
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode = 'admin'
on conflict do nothing;

-- 9. Task Statuses
insert into ms_task_statuses (pcode, name, color, order_no, is_terminal) values
('new', 'Новая', '#3b82f6', 10, false),
('in_progress', 'В работе', '#eab308', 20, false),
('done', 'Выполнена', '#22c55e', 30, true),
('cancelled', 'Отменена', '#ef4444', 40, true)
on conflict (pcode) do nothing;
