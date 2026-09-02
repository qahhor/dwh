-- ============================================================================
-- DWH Platform - V007: Dynamic Task Statuses and Types
-- ============================================================================

-- 1. Relax pcode check on ms_task_statuses to allow user-defined custom statuses
alter table ms_task_statuses drop constraint if exists ms_task_statuses_pcode_check;

-- 2. Create ms_task_types table for dynamic task types
create table if not exists ms_task_types (
    id bigint generated always as identity primary key,
    code text not null unique,
    name text not null,
    icon text not null default 'task_alt',
    color text not null default '#6366f1',
    order_no int not null default 0,
    is_system boolean not null default false,
    created_at timestamptz not null default now()
);

-- 3. Seed default task types
insert into ms_task_types (code, name, icon, color, order_no, is_system) values
('task', 'Задача', 'task_alt', '#6366f1', 10, true),
('bug', 'Ошибка', 'bug_report', '#ef4444', 20, true),
('feature', 'Улучшение', 'bolt', '#f59e0b', 30, true),
('research', 'Исследование', 'science', '#8b5cf6', 40, true)
on conflict (code) do nothing;
