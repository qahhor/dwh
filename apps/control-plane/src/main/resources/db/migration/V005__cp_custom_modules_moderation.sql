-- ============================================================================
-- V005: Модерация и подтверждение пользовательских модулей клиентов (CP Moderation)
-- ============================================================================

create table if not exists cp_instance_modules (
    id bigserial primary key,
    instance_id bigint not null references cp_instances(id) on delete cascade,
    client_code text not null,
    module_code text not null,
    name text not null,
    version text not null default '1.0.0',
    description text,
    category text not null default 'custom',
    icon text not null default 'extension',
    route_path text not null,
    entrypoint_url text not null,
    permissions_json jsonb not null default '[]'::jsonb,
    status text not null default 'PENDING_APPROVAL', -- PENDING_APPROVAL, APPROVED, REJECTED, SUSPENDED
    moderation_notes text,
    reviewed_by text,
    reviewed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_cp_instance_module unique(instance_id, module_code)
);

create index if not exists idx_cp_instance_modules_status on cp_instance_modules(status);
create index if not exists idx_cp_instance_modules_instance on cp_instance_modules(instance_id);
