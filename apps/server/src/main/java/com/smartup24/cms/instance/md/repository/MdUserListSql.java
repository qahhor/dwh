package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The SQL of the user registry list ({@code iam.users}): the columns a page selects and its predicate. The rows
 * are read by {@link MdUserRepository#mapUser}, so the columns stay in step with it.
 */
public final class MdUserListSql {

    private MdUserListSql() {}

    /** Columns of a user row as {@link MdUserRepository#mapUser} reads them; the registry list selects them. */
    public static final String LIST_COLUMNS = """
            md_users.id, md_users.name, md_users.login, md_users.email, md_users.phone, md_users.password_hash,
            md_users.state, md_users.manager_id, md_users.language, md_users.timezone, md_users.avatar_file_id,
            md_users.attributes::text as attributes_str, md_users.is_2fa_enabled, md_users.force_password_change,
            md_users.password_changed_at, md_users.created_at, md_users.modified_at, md_users.created_by,
            md_users.modified_by, md_users.auth_version""";

    /** The flat filters the list took before the registry, kept so existing callers keep working. */
    public record LegacyUserFilters(String state, Long roleId, Long managerId, Boolean is2faEnabled) {

        public static LegacyUserFilters none() {
            return new LegacyUserFilters(null, null, null, null);
        }

        /** Canonical form for the cursor fingerprint; null when no filter is set. */
        public String canonical() {
            String value = (state == null || state.isBlank() ? "" : "state=" + state.strip())
                    + (roleId == null ? "" : ";role=" + roleId)
                    + (managerId == null ? "" : ";manager=" + managerId)
                    + (is2faEnabled == null ? "" : ";2fa=" + is2faEnabled);
            return value.isEmpty() ? null : value;
        }
    }

    /**
     * The data scope (ADR-0013) and the flat filters as one predicate for the registry page. They go into the
     * same SQL, so a page and its total only ever see visible users.
     */
    public static QueryPlan.SqlFragment listPredicate(ScopeFilter scope, LegacyUserFilters filters) {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        if (!scope.isUnrestricted()) {
            sql.append(scope.sql());
            if (scope.bindsUserId()) {
                params.put("scopeUserId", scope.userId());
            }
        }
        if (filters.state() != null && !filters.state().isBlank()) {
            sql.append(" and md_users.state = :state");
            params.put("state", filters.state().strip());
        }
        if (filters.roleId() != null) {
            sql.append(
                    " and exists (select 1 from md_user_roles ur where ur.user_id = md_users.id and ur.role_id = :roleId)");
            params.put("roleId", filters.roleId());
        }
        if (filters.managerId() != null) {
            sql.append(" and md_users.manager_id = :managerId");
            params.put("managerId", filters.managerId());
        }
        if (filters.is2faEnabled() != null) {
            sql.append(" and md_users.is_2fa_enabled = :is2faEnabled");
            params.put("is2faEnabled", filters.is2faEnabled());
        }
        return new QueryPlan.SqlFragment(sql.toString(), params);
    }
}
