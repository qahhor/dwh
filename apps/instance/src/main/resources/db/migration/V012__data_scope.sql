-- ============================================================================
-- V012: скоуп данных — оргструктура, правило видимости, эффективный скоуп
--
-- ADR-0013. До этой миграции модель доступа отвечала только на вопрос
-- «можно ли открыть форму», но не на вопрос «какие строки в ней твои».
-- Дашборды Этапа 3 без второго вопроса существовать не могут.
--
-- Совместимость: все существующие роли получают правило ALL, то есть
-- поведение экземпляра не меняется ни на строку. Механизм включается тогда,
-- когда администратор осознанно поставит роли другое правило.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Оргструктура: дерево произвольной глубины, один корень на экземпляр
-- ----------------------------------------------------------------------------
create table md_org_units (
    id bigint generated always as identity primary key,
    parent_id bigint references md_org_units(id) on delete restrict,
    code text not null unique,
    name text not null,
    -- Вид узла словарём, а не enum: у разных клиентов разное число уровней,
    -- и добавление уровня не должно быть релизом приложения.
    kind text not null default 'department',
    state text not null default 'A' check (state in ('A', 'P')),
    order_no int not null default 0,
    created_at timestamptz not null default now(),
    modified_at timestamptz not null default now(),
    -- Узел не может быть родителем самому себе. Более длинные циклы ловит
    -- приложение: в PostgreSQL нет декларативного способа запретить цикл.
    constraint md_org_units_no_self_parent check (parent_id is null or parent_id <> id)
);

create index md_org_units_parent_idx on md_org_units (parent_id);
create index md_org_units_state_idx on md_org_units (state) where state = 'A';

-- Ровно один корень: экземпляр принадлежит одному клиенту (ADR-0004),
-- второе дерево означало бы либо ошибку данных, либо multi-tenancy.
create unique index md_org_units_single_root_idx on md_org_units ((parent_id is null))
    where parent_id is null;

-- ----------------------------------------------------------------------------
-- 2. Позиция пользователя в дереве (может быть несколько узлов)
-- ----------------------------------------------------------------------------
create table md_user_org_units (
    user_id bigint not null references md_users(id) on delete cascade,
    org_unit_id bigint not null references md_org_units(id) on delete cascade,
    primary key (user_id, org_unit_id)
);

create index md_user_org_units_unit_idx on md_user_org_units (org_unit_id);

-- ----------------------------------------------------------------------------
-- 3. Правило видимости у роли
--
--   ALL     — все строки экземпляра, оргструктура игнорируется
--   SUBTREE — свои узлы и всё, что под ними
--   UNITS   — только свои узлы, без потомков
--   SELF    — только строки, где пользователь автор или исполнитель
-- ----------------------------------------------------------------------------
create table md_role_scope_rules (
    role_id bigint primary key references md_roles(id) on delete cascade,
    rule text not null default 'ALL' check (rule in ('ALL', 'SUBTREE', 'UNITS', 'SELF')),
    modified_at timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- 4. Материализация: правило пользователя и развёрнутый список видимых узлов
--
-- Ровно та же механика, что у md_effective_permissions: пересчёт в той же
-- транзакции, что и изменение, плюс инкремент permissions_version.
-- Для ALL и SELF строки в md_effective_scope не создаются: первое ничего
-- не ограничивает, второе не опирается на дерево.
-- ----------------------------------------------------------------------------
create table md_user_scope (
    user_id bigint primary key references md_users(id) on delete cascade,
    rule text not null default 'ALL' check (rule in ('ALL', 'SUBTREE', 'UNITS', 'SELF')),
    recalculated_at timestamptz not null default now()
);

create table md_effective_scope (
    user_id bigint not null references md_users(id) on delete cascade,
    org_unit_id bigint not null references md_org_units(id) on delete cascade,
    primary key (user_id, org_unit_id)
);

create index md_effective_scope_unit_idx on md_effective_scope (org_unit_id);

-- ----------------------------------------------------------------------------
-- 5. Привязка скоупируемых сущностей к узлу
--
-- Колонка nullable: строка без узла видна только правилу ALL. Это осознанно —
-- иначе миграция обязана была бы придумать узел для каждой существующей строки.
-- ----------------------------------------------------------------------------
alter table md_users add column org_unit_id bigint references md_org_units(id) on delete set null;
create index md_users_org_unit_idx on md_users (org_unit_id);

-- ----------------------------------------------------------------------------
-- 6. Совместимость: поведение существующего экземпляра не меняется
-- ----------------------------------------------------------------------------
insert into md_role_scope_rules (role_id, rule)
select id, 'ALL' from md_roles
on conflict (role_id) do nothing;

insert into md_user_scope (user_id, rule)
select id, 'ALL' from md_users
on conflict (user_id) do nothing;

-- ----------------------------------------------------------------------------
-- 7. Каталог форм и прав: управление оргструктурой — отдельная форма
-- ----------------------------------------------------------------------------
insert into md_forms (code, module, name) values ('iam.org_units', 'md', 'Оргструктура')
on conflict (code) do nothing;

insert into md_form_actions (form_code, action, name) values
    ('iam.org_units', 'view', 'Просмотр оргструктуры'),
    ('iam.org_units', 'create', 'Создание узла'),
    ('iam.org_units', 'update', 'Редактирование узла'),
    ('iam.org_units', 'delete', 'Удаление узла'),
    ('iam.org_units', 'assign', 'Назначение сотрудников и правил видимости')
on conflict (form_code, action) do nothing;

-- Права на оргструктуру — только у администратора: смена правила видимости
-- меняет видимость данных так же радикально, как выдача права.
insert into md_role_permissions (role_id, form_code, action)
select r.id, a.form_code, a.action
from md_roles r
cross join md_form_actions a
where r.pcode = 'admin' and a.form_code = 'iam.org_units'
on conflict do nothing;

-- Аудитор смотрит, но не меняет — согласовано с определением роли.
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'iam.org_units', 'view'
from md_roles r
where r.pcode = 'auditor'
on conflict do nothing;
