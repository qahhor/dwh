-- ============================================================================
-- DWH Platform - Core Instance Database Migration V001
-- PostgreSQL 18 Compatible
-- Naming standard: Biruni & Smartup (md, kauth, ms, mf, audit, kwh)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Extensions
-- ----------------------------------------------------------------------------
create extension if not exists "pgcrypto";
create extension if not exists "pg_trgm";

-- ----------------------------------------------------------------------------
-- 2. Module: md (Master Data, Instance Info, Settings, Custom Fields)
-- ----------------------------------------------------------------------------
create table md_instance_info (
    client_code text primary key,
    client_name text not null,
    resource_profile text not null check (resource_profile in ('S', 'M', 'L')),
    license_token text not null,
    license_status text not null default 'ACTIVE' check (license_status in ('ACTIVE', 'GRACE', 'READ_ONLY')),
    grace_until timestamptz,
    cp_public_keys jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    modified_at timestamptz not null default now()
);

create table md_custom_fields (
    id bigint generated always as identity primary key,
    entity_type text not null check (entity_type in ('USER', 'TASK', 'PROJECT')),
    code text not null,
    name text not null,
    field_type text not null check (field_type in ('string', 'number', 'date', 'boolean', 'select', 'user_ref')),
    is_required boolean not null default false,
    default_value text,
    options_json jsonb not null default '[]'::jsonb,
    order_no int not null default 0,
    created_at timestamptz not null default now(),
    unique (entity_type, code)
);

-- ----------------------------------------------------------------------------
-- 3. Module: mf (Media & Files) - Pre-created for avatar/comment foreign keys
-- ----------------------------------------------------------------------------
create table mf_files (
    id uuid primary key default gen_random_uuid(),
    sha256 text not null unique,
    original_name text not null,
    size_bytes bigint not null check (size_bytes > 0),
    mime_type text not null,
    storage_bucket text not null,
    storage_key text not null,
    created_at timestamptz not null default now(),
    created_by bigint
);

-- ----------------------------------------------------------------------------
-- 4. Module: md & kauth (Users, Sessions, Tokens, 2FA)
-- ----------------------------------------------------------------------------
create table md_users (
    id bigint generated always as identity primary key,
    name text not null,
    login text not null unique,
    email text not null unique,
    phone text,
    password_hash text,
    state text not null default 'A' check (state in ('A', 'P')),
    manager_id bigint references md_users(id) on delete set null,
    language text not null default 'ru',
    timezone text not null default 'UTC',
    avatar_file_id uuid references mf_files(id) on delete set null,
    attributes jsonb not null default '{}'::jsonb,
    is_2fa_enabled boolean not null default false,
    force_password_change boolean not null default false,
    password_changed_at timestamptz,
    created_at timestamptz not null default now(),
    modified_at timestamptz not null default now(),
    created_by bigint references md_users(id),
    modified_by bigint references md_users(id)
);

alter table mf_files add constraint fk_mf_files_created_by foreign key (created_by) references md_users(id);

create unique index md_users_phone_active_uq on md_users (phone) where (state = 'A' and phone is not null);
create index md_users_attributes_gin_idx on md_users using gin (attributes jsonb_path_ops);

create table md_settings (
    id bigint generated always as identity primary key,
    user_id bigint references md_users(id) on delete cascade,
    key text not null,
    value text not null,
    unique (user_id, key)
);

create table idempotency_keys (
    key uuid primary key,
    user_id bigint references md_users(id),
    request_hash text not null,
    response_status int not null,
    response_body jsonb not null,
    created_at timestamptz not null default now()
);
create index idempotency_keys_created_idx on idempotency_keys (created_at desc);

create table kauth_sessions (
    id bigint generated always as identity primary key,
    user_id bigint not null references md_users(id) on delete cascade,
    token_hash text not null unique,
    ip inet not null,
    user_agent text not null,
    device_info text,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    closed_at timestamptz
);
create index kauth_sessions_user_active_idx on kauth_sessions (user_id) where closed_at is null;

create table kauth_login_attempts (
    id bigint generated always as identity primary key,
    login text not null,
    ip inet not null,
    is_success boolean not null,
    failure_reason text,
    attempt_at timestamptz not null default now()
);
create index kauth_login_attempts_ip_time_idx on kauth_login_attempts (ip, attempt_at desc);

create table kauth_otp_codes (
    id bigint generated always as identity primary key,
    user_id bigint not null references md_users(id) on delete cascade,
    channel text not null check (channel in ('telegram', 'sms')),
    code_hash text not null,
    attempts_left int not null default 3,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    is_used boolean not null default false
);

create table kauth_api_tokens (
    id bigint generated always as identity primary key,
    user_id bigint not null references md_users(id) on delete cascade,
    name text not null,
    token_prefix text not null,
    token_hash text not null unique,
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    last_used_at timestamptz,
    revoked_at timestamptz
);

create table kauth_password_reset_codes (
    id bigint generated always as identity primary key,
    user_id bigint not null references md_users(id) on delete cascade,
    code_hash text not null unique,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    is_used boolean not null default false
);

create table kauth_user_channels (
    id bigint generated always as identity primary key,
    user_id bigint not null references md_users(id) on delete cascade,
    channel text not null check (channel in ('telegram', 'sms', 'email')),
    address text not null,
    is_verified boolean not null default false,
    created_at timestamptz not null default now(),
    unique (user_id, channel)
);

-- ----------------------------------------------------------------------------
-- 5. Module: md (RBAC Forms, Actions, Roles, Permissions)
-- ----------------------------------------------------------------------------
create table md_forms (
    code text primary key,
    module text not null,
    name text not null
);

create table md_form_actions (
    form_code text not null references md_forms(code) on delete cascade,
    action text not null,
    name text not null,
    primary key (form_code, action)
);

create table md_roles (
    id bigint generated always as identity primary key,
    name text not null unique,
    pcode text unique,
    state text not null default 'A' check (state in ('A', 'P')),
    order_no int not null default 0,
    created_at timestamptz not null default now(),
    modified_at timestamptz not null default now()
);

create table md_role_permissions (
    role_id bigint not null references md_roles(id) on delete cascade,
    form_code text not null,
    action text not null,
    primary key (role_id, form_code, action),
    foreign key (form_code, action) references md_form_actions(form_code, action) on delete cascade
);

create table md_user_roles (
    user_id bigint not null references md_users(id) on delete cascade,
    role_id bigint not null references md_roles(id) on delete cascade,
    primary key (user_id, role_id)
);

create table md_user_permissions (
    user_id bigint not null references md_users(id) on delete cascade,
    form_code text not null,
    action text not null,
    primary key (user_id, form_code, action)
);

create table md_effective_permissions (
    user_id bigint not null references md_users(id) on delete cascade,
    form_code text not null,
    action text not null,
    source_role_id bigint references md_roles(id) on delete cascade,
    primary key (user_id, form_code, action)
);

create table md_user_permission_versions (
    user_id bigint primary key references md_users(id) on delete cascade,
    permissions_version bigint not null default 1,
    is_recalculating boolean not null default false
);

-- ----------------------------------------------------------------------------
-- 6. Module: ms (Tasks & Projects)
-- ----------------------------------------------------------------------------
create table ms_task_projects (
    id bigint generated always as identity primary key,
    name text not null unique,
    description text,
    state text not null default 'A' check (state in ('A', 'P')),
    attributes jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    created_by bigint references md_users(id)
);

create table ms_task_project_members (
    project_id bigint not null references ms_task_projects(id) on delete cascade,
    user_id bigint not null references md_users(id) on delete cascade,
    access_kind text not null check (access_kind in ('R', 'W')),
    primary key (project_id, user_id)
);

create table ms_task_statuses (
    id bigint generated always as identity primary key,
    pcode text unique check (pcode in ('new', 'in_progress', 'done', 'cancelled')),
    name text not null,
    color text not null,
    order_no int not null default 0,
    is_terminal boolean not null default false
);

create table ms_tasks (
    id bigint generated always as identity primary key,
    project_id bigint references ms_task_projects(id) on delete restrict,
    parent_task_id bigint references ms_tasks(id) on delete restrict,
    title text not null,
    description_markdown text not null default '',
    status_id bigint not null references ms_task_statuses(id),
    priority text not null default 'medium' check (priority in ('low', 'medium', 'high', 'critical')),
    reporter_id bigint not null references md_users(id),
    attributes jsonb not null default '{}'::jsonb,
    begin_time timestamptz,
    end_time timestamptz,
    resolved_time timestamptz,
    created_at timestamptz not null default now(),
    modified_at timestamptz not null default now(),
    created_by bigint references md_users(id),
    modified_by bigint references md_users(id),
    check (begin_time is null or end_time is null or begin_time <= end_time)
);
create index ms_tasks_attributes_gin_idx on ms_tasks using gin (attributes jsonb_path_ops);
create index ms_tasks_project_idx on ms_tasks (project_id);
create index ms_tasks_status_idx on ms_tasks (status_id);

create table ms_task_members (
    task_id bigint not null references ms_tasks(id) on delete cascade,
    user_id bigint not null references md_users(id) on delete cascade,
    involve_kind text not null check (involve_kind in ('R', 'E', 'P', 'A', 'O')),
    is_viewed boolean not null default false,
    primary key (task_id, user_id, involve_kind)
);
create unique index ms_task_single_responsible_uq on ms_task_members (task_id) where (involve_kind = 'R');

create table ms_task_comments (
    id bigint generated always as identity primary key,
    task_id bigint not null references ms_tasks(id) on delete cascade,
    user_id bigint not null references md_users(id),
    text_markdown text not null,
    created_at timestamptz not null default now()
);
create index ms_task_comments_task_idx on ms_task_comments (task_id, created_at asc);

create table ms_task_comment_files (
    comment_id bigint not null references ms_task_comments(id) on delete cascade,
    file_id uuid not null references mf_files(id) on delete cascade,
    primary key (comment_id, file_id)
);

-- ----------------------------------------------------------------------------
-- 7. Module: ms (Notifications, Transactional Outbox, Announcements)
-- ----------------------------------------------------------------------------
create table ms_notifications (
    id bigint generated always as identity primary key,
    user_id bigint not null references md_users(id) on delete cascade,
    type text not null check (type in ('info', 'success', 'warning', 'danger')),
    title text not null,
    body text not null,
    form_link text,
    source_code text,
    is_read boolean not null default false,
    created_at timestamptz not null default now()
);
create index ms_notifications_user_unread_idx on ms_notifications (user_id, created_at desc) where not is_read;

create table ms_notification_outbox (
    id bigint generated always as identity primary key,
    channel text not null check (channel in ('email', 'telegram', 'sms')),
    recipient text not null,
    template_code text not null,
    payload jsonb not null,
    status text not null default 'PENDING' check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD_LETTER')),
    attempts int not null default 0,
    max_attempts int not null default 5,
    next_attempt_at timestamptz not null default now(),
    idempotency_key uuid not null unique,
    last_error text,
    created_at timestamptz not null default now(),
    processed_at timestamptz
);
create index ms_outbox_worker_idx on ms_notification_outbox (next_attempt_at) where (status = 'PENDING');

create table ms_notification_prefs (
    user_id bigint not null references md_users(id) on delete cascade,
    event_type text not null,
    channel text not null,
    is_enabled boolean not null default true,
    primary key (user_id, event_type, channel)
);

create table ms_announcements_cache (
    id bigint primary key,
    title_json jsonb not null,
    body_json jsonb not null,
    banner_type text not null,
    published_at timestamptz not null,
    state text not null default 'published'
);

create table ms_announcement_reads (
    announcement_id bigint not null references ms_announcements_cache(id) on delete cascade,
    user_id bigint not null references md_users(id) on delete cascade,
    read_at timestamptz not null default now(),
    primary key (announcement_id, user_id)
);

-- ----------------------------------------------------------------------------
-- 8. Module: kwh (Outbound Webhooks)
-- ----------------------------------------------------------------------------
create table kwh_subscriptions (
    id bigint generated always as identity primary key,
    name text not null,
    target_url text not null,
    secret_token text not null,
    subscribed_events text[] not null,
    state text not null default 'A' check (state in ('A', 'P')),
    created_at timestamptz not null default now(),
    created_by bigint references md_users(id)
);

create table kwh_outbox (
    id bigint generated always as identity primary key,
    subscription_id bigint not null references kwh_subscriptions(id) on delete cascade,
    event_type text not null,
    payload jsonb not null,
    status text not null default 'PENDING' check (status in ('PENDING', 'SENT', 'FAILED', 'DEAD_LETTER')),
    attempts int not null default 0,
    max_attempts int not null default 5,
    next_attempt_at timestamptz not null default now(),
    last_error text,
    last_http_status int,
    created_at timestamptz not null default now(),
    processed_at timestamptz
);
create index kwh_outbox_worker_idx on kwh_outbox (next_attempt_at) where (status = 'PENDING');

create table kwh_logs (
    id bigint generated always as identity primary key,
    subscription_id bigint not null references kwh_subscriptions(id) on delete cascade,
    event_type text not null,
    http_status int not null,
    duration_ms int not null,
    is_success boolean not null,
    sent_at timestamptz not null default now()
);
create index kwh_logs_sent_idx on kwh_logs (sent_at desc);

-- ----------------------------------------------------------------------------
-- 9. Module: audit (Audit Log & Security Events)
-- ----------------------------------------------------------------------------
create table audit_log (
    id bigint generated always as identity,
    table_name text not null,
    row_pk text not null,
    event char(1) not null check (event in ('I', 'U', 'D')),
    changed_by bigint,
    session_id bigint,
    is_api boolean not null default false,
    changed_at timestamptz not null default now(),
    changed_columns text[],
    old_row jsonb,
    new_row jsonb,
    primary key (changed_at, id)
) partition by range (changed_at);

-- Initial Monthly Partition for audit_log (2026-08 to 2026-10 and default fallback)
create table audit_log_2026_08 partition of audit_log
    for values from ('2026-08-01 00:00:00+00') to ('2026-09-01 00:00:00+00');
create table audit_log_2026_09 partition of audit_log
    for values from ('2026-09-01 00:00:00+00') to ('2026-10-01 00:00:00+00');
create table audit_log_default partition of audit_log default;

create table security_events (
    id bigint generated always as identity primary key,
    event_type text not null,
    user_id bigint,
    ip inet not null,
    user_agent text,
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index security_events_time_idx on security_events (created_at desc);
