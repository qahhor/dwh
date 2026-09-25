set lock_timeout = '2s';
set statement_timeout = '60s';
-- ============================================================================
-- V117: Сохранённые представления списков (ADR-0016, роадмап п. 14).
-- Представление принадлежит одному пользователю и одному списку реестра полей:
-- колонки, сортировка и фильтр в state; сервер проверяет state по реестру.
-- ============================================================================

create table md_list_views (
    id bigint generated always as identity primary key,
    user_id bigint not null references md_users(id) on delete cascade,
    list_code text not null,
    name text not null,
    state jsonb not null,
    is_default boolean not null default false,
    lock_version integer not null default 0,
    created_at timestamptz not null default now(),
    modified_at timestamptz not null default now(),
    constraint md_list_views_ck_list_code check (list_code ~ '^[a-z][a-z0-9._-]{0,99}$'),
    constraint md_list_views_ck_name check (char_length(btrim(name)) between 1 and 80),
    constraint md_list_views_ck_state check (jsonb_typeof(state) = 'object')
);

create unique index md_list_views_name_uq on md_list_views (user_id, list_code, lower(btrim(name)));
create unique index md_list_views_default_uq on md_list_views (user_id, list_code) where is_default;
