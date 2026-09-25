set lock_timeout = '2s';
set statement_timeout = '60s';
-- ============================================================================
-- V118: Асинхронные выгрузки списков в xlsx и их журнал (ADR-0018, роадмап п. 22).
-- Выгрузка принадлежит пользователю, который её заказал; файл лежит в хранилище
-- экземпляра под storage_key и удаляется вместе со строкой, когда истёк срок.
-- ============================================================================

create table report_exports (
    id bigint generated always as identity primary key,
    public_id uuid not null default gen_random_uuid(),
    user_id bigint not null references md_users(id) on delete cascade,
    list_code text not null,
    request jsonb not null,
    state text not null default 'queued',
    rows_count integer,
    truncated boolean not null default false,
    file_name text,
    storage_key text,
    size_bytes bigint,
    error_code text,
    created_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz,
    expires_at timestamptz not null default now() + interval '7 days',
    constraint report_exports_ck_state check (state in ('queued', 'running', 'done', 'failed')),
    constraint report_exports_ck_list_code check (list_code ~ '^[a-z][a-z0-9._-]{0,99}$'),
    constraint report_exports_ck_request check (jsonb_typeof(request) = 'object'),
    constraint report_exports_ck_done check (state <> 'done' or (storage_key is not null and rows_count is not null))
);

create unique index report_exports_public_id_uq on report_exports (public_id);
create index report_exports_user_idx on report_exports (user_id, created_at desc);
create index report_exports_expires_idx on report_exports (expires_at);
