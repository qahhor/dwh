-- V009: Company and User storage quotas and file management
alter table md_instance_info add column if not exists storage_quota_bytes bigint not null default 53687091200; -- 50 GB
alter table md_roles add column if not exists storage_quota_bytes bigint;
alter table md_users add column if not exists storage_quota_bytes bigint;

-- Register actions for platform.files
insert into md_form_actions (form_code, action, name)
values
    ('platform.files', 'delete', 'Удаление файлов'),
    ('platform.files', 'manage_quotas', 'Управление квотами хранилища')
on conflict (form_code, action) do nothing;

-- Grant to admin role
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join (values ('platform.files', 'delete'), ('platform.files', 'manage_quotas')) as fa(form_code, action)
where r.pcode = 'admin'
on conflict (role_id, form_code, action) do nothing;

-- Grant delete to manager and user for their own files
insert into md_role_permissions (role_id, form_code, action)
select r.id, 'platform.files', 'delete'
from md_roles r
where r.pcode in ('manager', 'user')
on conflict (role_id, form_code, action) do nothing;
