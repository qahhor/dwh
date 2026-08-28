-- ============================================================================
-- DWH Platform - Central Control Plane Database Migration V001
-- PostgreSQL 18 Compatible
-- Naming standard: cp_*
-- ============================================================================

create extension if not exists "pgcrypto";

create table cp_clients (
    id bigint generated always as identity primary key,
    code text not null unique,
    name text not null,
    contacts jsonb not null default '{}'::jsonb,
    resource_profile text not null check (resource_profile in ('S', 'M', 'L')),
    created_at timestamptz not null default now()
);

create table cp_instances (
    id bigint generated always as identity primary key,
    client_id bigint not null references cp_clients(id) on delete cascade,
    environment text not null check (environment in ('production', 'staging', 'dev')),
    url text not null,
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'GRACE', 'READ_ONLY', 'DOWN')),
    last_heartbeat_at timestamptz,
    created_at timestamptz not null default now()
);
create index cp_instances_heartbeat_idx on cp_instances (last_heartbeat_at desc);

create table cp_instance_heartbeats (
    id bigint generated always as identity primary key,
    instance_id bigint not null references cp_instances(id) on delete cascade,
    app_version text not null,
    schema_version text not null,
    metrics jsonb not null default '{}'::jsonb,
    received_at timestamptz not null default now()
);
create index cp_heartbeats_time_idx on cp_instance_heartbeats (instance_id, received_at desc);

create table cp_licenses (
    id bigint generated always as identity primary key,
    client_id bigint not null references cp_clients(id) on delete cascade,
    kid text not null,
    valid_from timestamptz not null,
    valid_to timestamptz not null,
    grace_days int not null default 14,
    features text[] not null default '{}',
    signature text not null,
    created_at timestamptz not null default now()
);

create table cp_backup_verifications (
    id bigint generated always as identity primary key,
    client_id bigint not null references cp_clients(id) on delete cascade,
    is_success boolean not null,
    check_duration_sec int not null,
    details text,
    verified_at timestamptz not null default now()
);
create index cp_backup_verifications_time_idx on cp_backup_verifications (client_id, verified_at desc);

create table cp_announcements (
    id bigint generated always as identity primary key,
    banner_type text not null check (banner_type in ('info', 'warning', 'danger')),
    state text not null default 'draft' check (state in ('draft', 'published', 'archived')),
    published_at timestamptz,
    created_at timestamptz not null default now()
);

create table cp_announcement_targets (
    announcement_id bigint not null references cp_announcements(id) on delete cascade,
    client_id bigint references cp_clients(id) on delete cascade, -- NULL = all clients
    primary key (announcement_id, client_id)
);

create table cp_announcement_contents (
    announcement_id bigint not null references cp_announcements(id) on delete cascade,
    language text not null check (language in ('ru', 'uz', 'en')),
    title text not null,
    body text not null,
    primary key (announcement_id, language)
);

create table cp_users (
    id bigint generated always as identity primary key,
    name text not null,
    login text not null unique,
    email text not null unique,
    password_hash text not null,
    state text not null default 'A' check (state in ('A', 'P')),
    created_at timestamptz not null default now()
);

create table cp_roles (
    id bigint generated always as identity primary key,
    code text not null unique check (code in ('cp-admin', 'cp-engineer', 'cp-editor')),
    name text not null
);

create table cp_user_roles (
    user_id bigint not null references cp_users(id) on delete cascade,
    role_id bigint not null references cp_roles(id) on delete cascade,
    primary key (user_id, role_id)
);
