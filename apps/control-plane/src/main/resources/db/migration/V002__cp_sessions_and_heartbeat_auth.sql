-- ============================================================================
-- V002: вход в control panel и аутентификация heartbeat от экземпляров
-- Основание: FR-CP-1…7. Expand-миграция: только добавления.
-- ============================================================================

-- Сессии сотрудников control plane (аналог kauth_sessions экземпляра)
create table cp_sessions (
    id           bigint generated always as identity primary key,
    user_id      bigint not null references cp_users(id) on delete cascade,
    token_hash   text not null unique,
    ip           inet not null,
    user_agent   text not null,
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    closed_at    timestamptz
);
create index cp_sessions_user_active_idx on cp_sessions (user_id) where closed_at is null;

-- Токен экземпляра для отправки heartbeat: экземпляр не имеет учётной записи
-- в control plane, он аутентифицируется собственным токеном (FR-INST-3).
-- Хранится только hash — как и все токены в системе.
alter table cp_instances add column if not exists heartbeat_token_hash text;
alter table cp_instances add column if not exists app_version text;
alter table cp_instances add column if not exists schema_version text;

-- Системные роли control plane (FR-CP-7)
insert into cp_roles (code, name) values
('cp-admin',    'Администратор платформы'),
('cp-engineer', 'Инженер эксплуатации'),
('cp-editor',   'Редактор объявлений')
on conflict (code) do nothing;
