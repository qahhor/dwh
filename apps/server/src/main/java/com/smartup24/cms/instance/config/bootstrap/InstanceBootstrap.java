package com.smartup24.cms.instance.config.bootstrap;

import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initializes the instance on first start (FR-INST-1):
 * instance_info and the first administrator are created from the deployment configuration,
 * not by migrations (no demo data and no well-known passwords).
 * Idempotent: a no-op on an already initialized instance.
 */
@Component
@Profile("!migrate")
@Order(10)
public class InstanceBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstanceBootstrap.class);

    private final JdbcClient jdbc;
    private final PasswordHasher passwordHasher;
    private final MdPermissionService permissionService;
    private final InstanceBootstrapProperties props;

    public InstanceBootstrap(
            JdbcClient jdbc,
            PasswordHasher passwordHasher,
            MdPermissionService permissionService,
            InstanceBootstrapProperties props) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
        this.permissionService = permissionService;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initInstanceInfo();
        initFirstAdmin();
        syncEffectivePermissions();
    }

    private void syncEffectivePermissions() {
        jdbc.sql("""
                insert into md_effective_permissions (user_id, form_code, action, source_role_id)
                select ur.user_id, rp.form_code, rp.action, rp.role_id
                from md_user_roles ur
                join md_roles r on r.id = ur.role_id and r.state = 'A'
                join md_role_permissions rp on rp.role_id = r.id
                on conflict (user_id, form_code, action) do nothing
                """).update();
    }

    private void initInstanceInfo() {
        Long count = jdbc.sql("select count(*) from md_instance_info")
                .query(Long.class)
                .single();
        if (count > 0) {
            return;
        }
        require(props.clientCode(), "smc.instance.client-code");
        require(props.clientName(), "smc.instance.client-name");
        jdbc.sql("""
                        insert into md_instance_info
                            (client_code, client_name, resource_profile)
                        values (:code, :name, :profile)
                        """)
                .param("code", props.clientCode())
                .param("name", props.clientName())
                .param("profile", props.resourceProfile())
                .update();
        log.info("instance_initialized client_code={} profile={}", props.clientCode(), props.resourceProfile());
    }

    private void initFirstAdmin() {
        Long users = jdbc.sql("select count(*) from md_users").query(Long.class).single();
        if (users > 0) {
            return;
        }
        require(props.adminLogin(), "smc.instance.admin-login");
        require(props.adminEmail(), "smc.instance.admin-email");
        require(props.adminPassword(), "smc.instance.admin-password");

        String hash = passwordHasher.hashPassword(props.adminPassword());
        Long userId = jdbc.sql("""
                        insert into md_users
                            (name, login, email, password_hash, state, language, timezone,
                             attributes, is_2fa_enabled, force_password_change)
                        values ('Administrator', :login, :email, :hash, 'A', 'ru', 'Asia/Tashkent',
                                '{}'::jsonb, false, true)
                        returning id
                        """)
                .param("login", props.adminLogin())
                .param("email", props.adminEmail())
                .param("hash", hash)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into md_user_roles (user_id, role_id)
                        select :userId, id from md_roles where pcode = 'admin'
                        """).param("userId", userId).update();

        permissionService.recalculateEffectivePermissions(userId);
        // The password is never written to the log (FR-OBS-4); force_password_change=true
        // makes the first sign-in require a change.
        log.info("first_admin_created login={} passwordChangeRequired=true", props.adminLogin());
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The instance is not initialized: set " + property
                    + " in the deployment configuration (FR-INST-1). "
                    + "Default values are refused (AUDIT-03 C-1/C-2).");
        }
    }
}
