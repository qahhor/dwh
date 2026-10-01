package com.smartup24.cms.instance.md.repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MdPermissionRepository {

    private final JdbcClient jdbcClient;

    public MdPermissionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void registerForm(String code, String module, String name) {
        jdbcClient
                .sql("""
                insert into md_forms (code, module, name, is_deprecated)
                values (:code, :module, :name, false)
                on conflict (code) do update
                    set module = :module, name = :name, is_deprecated = false
                """)
                .param("code", code)
                .param("module", module)
                .param("name", name)
                .update();
    }

    public void registerFormAction(String formCode, String action, String name) {
        jdbcClient
                .sql("""
                insert into md_form_actions (form_code, action, name, is_deprecated)
                values (:formCode, :action, :name, false)
                on conflict (form_code, action) do update
                    set name = :name, is_deprecated = false
                """)
                .param("formCode", formCode)
                .param("action", action)
                .param("name", name)
                .update();
    }

    /**
     * Marks obsolete everything that is not among the pairs declared in code (FR-PERM-1).
     *
     * There is deliberately no deletion: deleting a form would cascade to permissions already
     * granted, and temporarily renaming an endpoint would silently take people's access away.
     *
     * @param livePairs pairs of the form {@code form.action} found among @RequiresPermission
     * @return how many records became obsolete in this pass
     */
    public int deprecateMissing(Set<String> livePairs) {
        if (livePairs.isEmpty()) {
            // No pairs in code means there are no live permissions at all.
            // The situation is abnormal, but it must be marked honestly, not hidden.
            return jdbcClient
                            .sql("update md_form_actions set is_deprecated = true where not is_deprecated")
                            .update()
                    + jdbcClient
                            .sql("update md_forms set is_deprecated = true where not is_deprecated")
                            .update();
        }

        Set<String> liveForms = livePairs.stream()
                .map(pair -> pair.substring(0, pair.lastIndexOf('.')))
                .collect(Collectors.toSet());

        int actions = jdbcClient
                .sql("""
                update md_form_actions
                set is_deprecated = true
                where not is_deprecated and (form_code || '.' || action) <> all (:pairs)
                """)
                .param("pairs", livePairs.toArray(new String[0]))
                .update();

        int forms = jdbcClient
                .sql("""
                update md_forms
                set is_deprecated = true
                where not is_deprecated and code <> all (:forms)
                """)
                .param("forms", liveForms.toArray(new String[0]))
                .update();

        return actions + forms;
    }

    /** Live catalog pairs: what can actually be granted (FR-PERM-1). */
    public Set<String> getGrantablePairs() {
        return new HashSet<>(jdbcClient.sql("""
                select fa.form_code || '.' || fa.action
                from md_form_actions fa
                join md_forms f on f.code = fa.form_code
                where not fa.is_deprecated and not f.is_deprecated
                """).query(String.class).list());
    }

    public Set<String> getEffectivePermissionsForUser(Long userId) {
        return jdbcClient.sql("""
                select form_code || '.' || action as perm
                from md_effective_permissions
                where user_id = :userId
                """).param("userId", userId).query(String.class).set();
    }

    public long getPermissionVersion(Long userId) {
        return jdbcClient
                .sql("""
                select permissions_version
                from md_user_permission_versions
                where user_id = :userId
                """)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                // No row means version 0, NOT 1: the first recalculation inserts 1,
                // and the version must grow, otherwise the permission cache is not invalidated
                // and granted permissions do not take effect (FR-PERM-6).
                .orElse(0L);
    }

    public void recalculateEffectivePermissions(Long userId) {
        // Materialize effective permissions = Union of Role Permissions + Personal Permissions
        jdbcClient
                .sql("delete from md_effective_permissions where user_id = :userId")
                .param("userId", userId)
                .update();

        // 1. Role permissions
        jdbcClient.sql("""
                insert into md_effective_permissions (user_id, form_code, action, source_role_id)
                select ur.user_id, rp.form_code, rp.action, rp.role_id
                from md_user_roles ur
                join md_roles r on r.id = ur.role_id and r.state = 'A'
                join md_role_permissions rp on rp.role_id = r.id
                where ur.user_id = :userId
                on conflict (user_id, form_code, action) do nothing
                """).param("userId", userId).update();

        // 2. Personal permissions
        jdbcClient.sql("""
                insert into md_effective_permissions (user_id, form_code, action, source_role_id)
                select up.user_id, up.form_code, up.action, null
                from md_user_permissions up
                where up.user_id = :userId
                on conflict (user_id, form_code, action) do nothing
                """).param("userId", userId).update();

        // 3. Bump version
        jdbcClient.sql("""
                insert into md_user_permission_versions (user_id, permissions_version, is_recalculating)
                values (:userId, 1, false)
                on conflict (user_id) do update
                set permissions_version = md_user_permission_versions.permissions_version + 1,
                    is_recalculating = false
                """).param("userId", userId).update();
    }

    public List<FormTreeItem> getAllFormsWithActions() {
        return jdbcClient
                .sql("""
                select f.code as form_code, f.module, f.name as form_name,
                       fa.action, fa.name as action_name,
                       (f.is_deprecated or fa.is_deprecated) as is_deprecated
                from md_forms f
                join md_form_actions fa on fa.form_code = f.code
                order by f.module asc, f.code asc, fa.action asc
                """)
                .query((rs, rowNum) -> new FormTreeItem(
                        rs.getString("form_code"),
                        rs.getString("module"),
                        rs.getString("form_name"),
                        rs.getString("action"),
                        rs.getString("action_name"),
                        rs.getBoolean("is_deprecated")))
                .list();
    }

    // ------------------------------------------------------------------
    // Personal permissions on top of roles (FR-PERM-5)
    // ------------------------------------------------------------------

    public Set<String> getUserPersonalPermissions(Long userId) {
        return new HashSet<>(jdbcClient
                .sql("select form_code || '.' || action from md_user_permissions where user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .list());
    }

    /** Replaces the whole set of personal permissions (PUT semantics). */
    public void replaceUserPermissions(Long userId, List<MdRoleRepository.PermissionPair> permissions) {
        jdbcClient
                .sql("delete from md_user_permissions where user_id = :userId")
                .param("userId", userId)
                .update();
        for (var p : permissions) {
            jdbcClient
                    .sql("""
                            insert into md_user_permissions (user_id, form_code, action)
                            values (:userId, :formCode, :action)
                            on conflict do nothing
                            """)
                    .param("userId", userId)
                    .param("formCode", p.formCode())
                    .param("action", p.action())
                    .update();
        }
    }

    /**
     * Effective permissions with their source, for the "permissions as the user
     * sees them" screen (FR-PERM-10): it shows where each permission came from.
     */
    public List<EffectivePermissionItem> getEffectivePermissionsWithSource(Long userId) {
        return jdbcClient
                .sql("""
                        select ep.form_code, ep.action, ep.source_role_id, r.name as role_name
                        from md_effective_permissions ep
                        left join md_roles r on r.id = ep.source_role_id
                        where ep.user_id = :userId
                        order by ep.form_code, ep.action
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new EffectivePermissionItem(
                        rs.getString("form_code"),
                        rs.getString("action"),
                        rs.getString("role_name") != null ? "role:" + rs.getString("role_name") : "personal"))
                .list();
    }

    public record EffectivePermissionItem(String formCode, String action, String source) {}

    public record FormTreeItem(
            String formCode, String module, String formName, String action, String actionName, boolean isDeprecated) {}
}
