-- ============================================================================
-- V006: Fleet Foundation control-plane schema.
-- Expand-only: legacy heartbeat credentials and raw metrics remain available
-- until application traffic has moved to the typed contracts.
-- ============================================================================

create table cp_releases (
    id uuid primary key default gen_random_uuid(),
    version text not null unique,
    source_commit char(40) not null,
    manifest_digest text not null unique,
    manifest_location text not null,
    verification_bundle_digest text not null,
    config_schema_version text not null,
    minimum_agent_version text not null,
    deployment_modes text[] not null,
    status text not null default 'DRAFT'
        check (status in ('DRAFT', 'READY', 'REVOKED')),
    created_by_user_id bigint references cp_users(id),
    created_by_identity text not null,
    created_at timestamptz not null default now(),
    ready_at timestamptz,
    revoked_at timestamptz
);

create table cp_release_components (
    release_id uuid not null references cp_releases(id) on delete cascade,
    component_name text not null,
    image_reference text not null,
    image_digest text not null,
    sbom_digest text not null,
    provenance_digest text not null,
    minimum_schema_version text,
    maximum_rollback_schema_version text,
    primary key (release_id, component_name),
    check (image_reference like '%@sha256:%')
);

create table cp_instance_enrollment_tokens (
    id bigint generated always as identity primary key,
    instance_id bigint not null references cp_instances(id) on delete cascade,
    token_hash char(64) not null unique,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_by bigint not null references cp_users(id),
    created_at timestamptz not null default now()
);

create table cp_instance_credentials (
    id bigint generated always as identity primary key,
    instance_id bigint not null references cp_instances(id) on delete cascade,
    credential_hash char(64) not null unique,
    activated_at timestamptz not null default now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    predecessor_id bigint references cp_instance_credentials(id),
    successor_id bigint references cp_instance_credentials(id),
    last_used_at timestamptz,
    created_at timestamptz not null default now()
);
create index cp_instance_credentials_active_hash_idx
    on cp_instance_credentials(credential_hash)
    where revoked_at is null;

insert into cp_instance_credentials(instance_id, credential_hash)
select id, heartbeat_token_hash
from cp_instances
where heartbeat_token_hash is not null
on conflict (credential_hash) do nothing;

create table cp_instance_backup_reports (
    id bigint generated always as identity primary key,
    backup_id uuid not null unique,
    instance_id bigint not null references cp_instances(id) on delete cascade,
    artifact_status text not null
        check (artifact_status in ('UPLOADED', 'VERIFIED', 'FAILED')),
    checksum_sha256 char(64),
    duration_sec int not null check (duration_sec between 0 and 86400),
    reason_code text,
    completed_at timestamptz not null,
    received_at timestamptz not null default now(),
    verified_at timestamptz,
    check ((artifact_status = 'UPLOADED' and checksum_sha256 is not null)
        or artifact_status in ('VERIFIED', 'FAILED'))
);
create index cp_instance_backup_reports_instance_time_idx
    on cp_instance_backup_reports(instance_id, received_at desc);

alter table cp_instances
    add column if not exists deployment_mode text not null default 'MANAGED_CLOUD'
        check (deployment_mode in ('MANAGED_CLOUD', 'CUSTOMER_HOSTED')),
    add column if not exists jurisdiction text,
    add column if not exists cloud_provider text,
    add column if not exists storage_provider text,
    add column if not exists edge_provider text,
    add column if not exists support_tier text not null default 'MANAGED_995',
    add column if not exists current_release_id uuid references cp_releases(id),
    add column if not exists current_config_version text,
    add column if not exists current_generation bigint not null default 0,
    add column if not exists lifecycle_status text not null default 'REGISTERED'
        check (lifecycle_status in (
            'REGISTERED', 'ENROLLING', 'ACTIVE', 'SUSPENDED',
            'OFFBOARDING', 'DELETED'
        ));

alter table cp_instance_heartbeats
    add column if not exists release_version text,
    add column if not exists config_version text,
    add column if not exists component_health jsonb not null default '{}'::jsonb,
    add column if not exists storage_used_bytes bigint
        check (storage_used_bytes >= 0),
    add column if not exists storage_quota_bytes bigint
        check (storage_quota_bytes >= 0),
    add column if not exists last_backup_at timestamptz,
    add column if not exists backup_status text
        check (backup_status in ('UNKNOWN', 'UPLOADED', 'VERIFIED', 'FAILED')),
    add column if not exists tunnel_status text
        check (tunnel_status in ('UP', 'DEGRADED', 'DOWN', 'UNKNOWN')),
    add column if not exists agent_status text
        check (agent_status in ('UP', 'DEGRADED', 'DOWN', 'UNKNOWN')),
    add column if not exists deployment_state text,
    add column if not exists active_users bigint
        check (active_users >= 0),
    add column if not exists outbox_pending bigint
        check (outbox_pending >= 0),
    add column if not exists outbox_dead_letter bigint
        check (outbox_dead_letter >= 0);
create index cp_instance_heartbeats_retention_idx
    on cp_instance_heartbeats(received_at);

create table cp_instance_targets (
    instance_id bigint primary key references cp_instances(id) on delete cascade,
    generation bigint not null check (generation > 0),
    desired_release_id uuid not null references cp_releases(id),
    config_version text not null,
    rollout_ring text not null check (rollout_ring in ('R0', 'R1', 'R2', 'R3')),
    maintenance_week_of_month smallint not null
        check (maintenance_week_of_month between 1 and 5),
    maintenance_day_of_week smallint not null
        check (maintenance_day_of_week between 1 and 7),
    maintenance_start time not null,
    maintenance_duration_minutes smallint not null
        check (maintenance_duration_minutes between 15 and 240),
    maintenance_timezone text not null,
    requested_by bigint not null references cp_users(id),
    requested_at timestamptz not null default now(),
    unique(instance_id, generation)
);

create table cp_deployments (
    id uuid primary key default gen_random_uuid(),
    instance_id bigint not null references cp_instances(id) on delete cascade,
    release_id uuid not null references cp_releases(id),
    generation bigint not null,
    previous_release_id uuid references cp_releases(id),
    runner_identity text,
    status text not null check (status in (
        'REQUESTED', 'PREFLIGHT', 'PREFLIGHT_FAILED', 'BACKUP_VERIFIED',
        'BACKUP_FAILED', 'MIGRATING', 'DEPLOYING', 'VERIFYING', 'SUCCEEDED',
        'ROLLING_BACK', 'ROLLED_BACK', 'RECOVERY_REQUIRED', 'CANCELLED'
    )),
    reason_code text,
    technical_log_reference text,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    unique(instance_id, generation)
);
create index cp_deployments_status_time_idx
    on cp_deployments(status, created_at desc);

create table cp_deployment_events (
    deployment_id uuid not null references cp_deployments(id) on delete cascade,
    sequence_no bigint not null check (sequence_no > 0),
    idempotency_key text not null unique,
    status text not null,
    reason_code text,
    details text check (char_length(details) <= 4000),
    occurred_at timestamptz not null default now(),
    primary key (deployment_id, sequence_no)
);

create table cp_heartbeat_daily (
    instance_id bigint not null references cp_instances(id) on delete cascade,
    day date not null,
    sample_count bigint not null check (sample_count >= 0),
    max_storage_used_bytes bigint,
    max_active_users bigint,
    max_outbox_pending bigint,
    last_app_version text,
    last_schema_version text,
    primary key (instance_id, day)
);

create table cp_audit_events (
    id bigint generated always as identity primary key,
    actor_type text not null
        check (actor_type in ('OPERATOR', 'INSTANCE', 'BUILD_IDENTITY', 'SYSTEM')),
    actor_id text not null,
    action text not null,
    entity_type text not null,
    entity_id text not null,
    trace_id char(32),
    details jsonb not null default '{}'::jsonb,
    occurred_at timestamptz not null default now()
);
create index cp_audit_events_entity_time_idx
    on cp_audit_events(entity_type, entity_id, occurred_at desc);
