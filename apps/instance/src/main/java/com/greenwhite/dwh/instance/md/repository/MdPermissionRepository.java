package com.greenwhite.dwh.instance.md.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class MdPermissionRepository {

    private final JdbcClient jdbcClient;

    public MdPermissionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void registerForm(String code, String module, String name) {
        jdbcClient.sql("""
                insert into md_forms (code, module, name)
                values (:code, :module, :name)
                on conflict (code) do update set module = :module, name = :name
                """)
                .param("code", code)
                .param("module", module)
                .param("name", name)
                .update();
    }

    public void registerFormAction(String formCode, String action, String name) {
        jdbcClient.sql("""
                insert into md_form_actions (form_code, action, name)
                values (:formCode, :action, :name)
                on conflict (form_code, action) do update set name = :name
                """)
                .param("formCode", formCode)
                .param("action", action)
                .param("name", name)
                .update();
    }

    public Set<String> getEffectivePermissionsForUser(Long userId) {
        return jdbcClient.sql("""
                select form_code || '.' || action as perm
                from md_effective_permissions
                where user_id = :userId
                """)
                .param("userId", userId)
                .query(String.class)
                .set();
    }

    public long getPermissionVersion(Long userId) {
        return jdbcClient.sql("""
                select permissions_version
                from md_user_permission_versions
                where user_id = :userId
                """)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                // Нет строки — версия 0, а НЕ 1: первый пересчёт вставляет 1,
                // и версия обязана вырасти, иначе кэш прав не инвалидируется
                // и выданные права не вступят в силу (FR-PERM-6).
                .orElse(0L);
    }

    public void recalculateEffectivePermissions(Long userId) {
        // Materialize effective permissions = Union of Role Permissions + Personal Permissions
        jdbcClient.sql("delete from md_effective_permissions where user_id = :userId")
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
                """)
                .param("userId", userId)
                .update();

        // 2. Personal permissions
        jdbcClient.sql("""
                insert into md_effective_permissions (user_id, form_code, action, source_role_id)
                select up.user_id, up.form_code, up.action, null
                from md_user_permissions up
                where up.user_id = :userId
                on conflict (user_id, form_code, action) do nothing
                """)
                .param("userId", userId)
                .update();

        // 3. Bump version
        jdbcClient.sql("""
                insert into md_user_permission_versions (user_id, permissions_version, is_recalculating)
                values (:userId, 1, false)
                on conflict (user_id) do update
                set permissions_version = md_user_permission_versions.permissions_version + 1,
                    is_recalculating = false
                """)
                .param("userId", userId)
                .update();
    }

    public List<FormTreeItem> getAllFormsWithActions() {
        return jdbcClient.sql("""
                select f.code as form_code, f.module, f.name as form_name,
                       fa.action, fa.name as action_name
                from md_forms f
                join md_form_actions fa on fa.form_code = f.code
                order by f.module asc, f.code asc, fa.action asc
                """)
                .query((rs, rowNum) -> new FormTreeItem(
                        rs.getString("form_code"),
                        rs.getString("module"),
                        rs.getString("form_name"),
                        rs.getString("action"),
                        rs.getString("action_name")
                ))
                .list();
    }


    // ------------------------------------------------------------------
    // Персональные права поверх ролей (FR-PERM-5)
    // ------------------------------------------------------------------

    public Set<String> getUserPersonalPermissions(Long userId) {
        return new HashSet<>(jdbcClient.sql(
                        "select form_code || '.' || action from md_user_permissions where user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .list());
    }

    /** Полная замена набора персональных прав (семантика PUT из ТЗ-04). */
    public void replaceUserPermissions(Long userId, List<MdRoleRepository.PermissionPair> permissions) {
        jdbcClient.sql("delete from md_user_permissions where user_id = :userId")
                .param("userId", userId)
                .update();
        for (var p : permissions) {
            jdbcClient.sql("""
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
     * Эффективные права с указанием источника — экран «права глазами
     * пользователя» (FR-PERM-10): видно, откуда пришло каждое право.
     */
    public List<EffectivePermissionItem> getEffectivePermissionsWithSource(Long userId) {
        return jdbcClient.sql("""
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
                        rs.getString("role_name") != null
                                ? "role:" + rs.getString("role_name")
                                : "personal"))
                .list();
    }

    public record EffectivePermissionItem(String formCode, String action, String source) {}

    public record FormTreeItem(
            String formCode,
            String module,
            String formName,
            String action,
            String actionName
    ) {}
}
