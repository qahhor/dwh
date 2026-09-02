-- ============================================================================
-- V006: Синхронизация эффективных прав RBAC (FR-PERM-6)
-- ============================================================================

insert into md_effective_permissions (user_id, form_code, action, source_role_id)
select ur.user_id, rp.form_code, rp.action, rp.role_id
from md_user_roles ur
join md_roles r on r.id = ur.role_id and r.state = 'A'
join md_role_permissions rp on rp.role_id = r.id
on conflict (user_id, form_code, action) do nothing;
