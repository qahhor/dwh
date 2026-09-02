-- ============================================================================
-- V019: SmartupCMS unified open-source core
-- Forward-only migration from the former instance/control-plane model.
-- ============================================================================

-- 1. Convert the remotely-fed announcement cache into a local aggregate.
alter table ms_announcements_cache rename to ms_announcements;

update ms_announcements
set state = case lower(state)
    when 'draft' then 'DRAFT'
    when 'published' then 'PUBLISHED'
    when 'archived' then 'ARCHIVED'
    else 'ARCHIVED'
end;

update ms_announcements
set banner_type = case upper(banner_type)
    when 'INFO' then 'INFO'
    when 'WARNING' then 'WARNING'
    when 'CRITICAL' then 'CRITICAL'
    else 'INFO'
end;

alter table ms_announcements
    alter column state set default 'DRAFT',
    alter column published_at drop not null,
    add column created_by bigint references md_users(id) on delete set null,
    add column created_at timestamptz not null default now(),
    add column modified_at timestamptz not null default now(),
    add column archived_at timestamptz,
    add column lock_version bigint not null default 0,
    add constraint ms_announcements_state_ck
        check (state in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    add constraint ms_announcements_banner_type_ck
        check (banner_type in ('INFO', 'WARNING', 'CRITICAL')),
    add constraint ms_announcements_lifecycle_ck check (
        (state = 'DRAFT' and published_at is null and archived_at is null)
        or (state = 'PUBLISHED' and published_at is not null and archived_at is null)
        or (state = 'ARCHIVED')
    );

create sequence ms_announcements_id_seq;
select setval(
    'ms_announcements_id_seq',
    coalesce((select max(id) from ms_announcements), 0) + 1,
    false
);
alter sequence ms_announcements_id_seq owned by ms_announcements.id;
alter table ms_announcements
    alter column id set default nextval('ms_announcements_id_seq');

create index ms_announcements_state_published_idx
    on ms_announcements(state, published_at desc);

-- The read table and its foreign key follow the PostgreSQL table rename,
-- so existing read history remains valid without rebuilding the constraint.

-- 2. Preserve custom-module metadata but make every module inert. The old
-- approval reference is retained for audit/history under a provider-neutral name.
update md_custom_modules
set status = 'DISABLED', updated_at = now()
where status <> 'DISABLED';

alter table md_custom_modules
    rename column cp_ticket_id to legacy_approval_reference;

-- 3. One installation is one organization; licensing is not a runtime concern.
alter table md_instance_info
    drop column license_token,
    drop column license_status,
    drop column grace_until,
    drop column cp_public_keys;

-- 4. Local announcement administration permissions.
insert into md_form_actions (form_code, action, name) values
    ('platform.announcements', 'create',  'Создание объявления'),
    ('platform.announcements', 'update',  'Редактирование объявления'),
    ('platform.announcements', 'publish', 'Публикация объявления'),
    ('platform.announcements', 'archive', 'Архивация объявления')
on conflict (form_code, action) do nothing;

insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
join md_form_actions fa on fa.form_code = 'platform.announcements'
where r.pcode = 'admin'
on conflict do nothing;

insert into md_effective_permissions (user_id, form_code, action, source_role_id)
select ur.user_id, rp.form_code, rp.action, rp.role_id
from md_user_roles ur
join md_roles r on r.id = ur.role_id and r.state = 'A'
join md_role_permissions rp on rp.role_id = r.id
where rp.form_code = 'platform.announcements'
on conflict (user_id, form_code, action) do nothing;
