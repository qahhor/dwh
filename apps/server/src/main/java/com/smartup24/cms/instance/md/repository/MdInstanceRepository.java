package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.md.api.MdInstanceOrganization;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of an instance's first start (FR-INST-1): the instance record, the first administrator and the effective
 * permissions of every user. The wiring calls it only through {@code MdInstanceService} (ADR-0026).
 */
@Repository
public class MdInstanceRepository {

    private final JdbcClient jdbc;

    public MdInstanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether the instance record exists. */
    public boolean instanceRecorded() {
        return jdbc.sql("select exists (select 1 from md_instance_info)")
                .query(Boolean.class)
                .single();
    }

    /** Records the organization the instance serves. */
    public void recordInstance(String code, String name, String resourceProfile) {
        jdbc.sql("""
                        insert into md_instance_info (client_code, client_name, resource_profile)
                        values (:code, :name, :profile)
                        """)
                .param("code", code)
                .param("name", name)
                .param("profile", resourceProfile)
                .update();
    }

    /** The organization recorded at the first start, if it is recorded. */
    public Optional<MdInstanceOrganization> organization() {
        return jdbc.sql("""
                        select client_code as code,
                               client_name as name,
                               resource_profile
                        from md_instance_info
                        limit 1
                        """).query(MdInstanceOrganization.class).optional();
    }

    /** Whether any user exists. */
    public boolean anyUser() {
        return jdbc.sql("select exists (select 1 from md_users)")
                .query(Boolean.class)
                .single();
    }

    /** Inserts the first administrator, who must change the password at the first sign-in; returns its id. */
    public long insertFirstAdmin(String login, String email, String passwordHash) {
        return jdbc.sql("""
                        insert into md_users
                            (name, login, email, password_hash, state, language, timezone,
                             attributes, is_2fa_enabled, force_password_change)
                        values ('Administrator', :login, :email, :hash, 'A', 'ru', 'Asia/Tashkent',
                                '{}'::jsonb, false, true)
                        returning id
                        """)
                .param("login", login)
                .param("email", email)
                .param("hash", passwordHash)
                .query(Long.class)
                .single();
    }

    /** Grants the user the administrator role. */
    public void grantAdminRole(long userId) {
        jdbc.sql("""
                        insert into md_user_roles (user_id, role_id)
                        select :userId, id from md_roles where pcode = 'admin'
                        """).param("userId", userId).update();
    }

    /** Adds the effective permissions every active role of every user grants and that are not yet there. */
    public void addMissingEffectivePermissions() {
        jdbc.sql("""
                insert into md_effective_permissions (user_id, form_code, action, source_role_id)
                select ur.user_id, rp.form_code, rp.action, rp.role_id
                from md_user_roles ur
                join md_roles r on r.id = ur.role_id and r.state = 'A'
                join md_role_permissions rp on rp.role_id = r.id
                on conflict (user_id, form_code, action) do nothing
                """).update();
    }
}
