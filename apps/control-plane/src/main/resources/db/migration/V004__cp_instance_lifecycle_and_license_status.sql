-- ============================================================================
-- V004: Управление жизненным циклом и статусом лицензий инстансов
-- ============================================================================
alter table cp_instances drop constraint if exists cp_instances_status_check;
alter table cp_instances add column if not exists license_status text not null default 'ACTIVE';
alter table cp_instances add column if not exists license_expires_at timestamptz;
