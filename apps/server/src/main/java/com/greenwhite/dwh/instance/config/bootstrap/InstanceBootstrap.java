package com.greenwhite.dwh.instance.config.bootstrap;

import com.greenwhite.dwh.instance.md.service.PasswordHasher;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
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
 * Инициализация экземпляра при первом старте (FR-INST-1):
 * instance_info и первый администратор создаются из конфигурации развёртывания,
 * а не миграциями (AUDIT-03 C-1/C-2: никаких DEMO-данных и известных паролей).
 * Идемпотентен: на инициализированном экземпляре — no-op.
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

    public InstanceBootstrap(JdbcClient jdbc,
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
        Long count = jdbc.sql("select count(*) from md_instance_info").query(Long.class).single();
        if (count > 0) {
            return;
        }
        require(props.clientCode(), "dwh.instance.client-code");
        require(props.clientName(), "dwh.instance.client-name");
        jdbc.sql("""
                        insert into md_instance_info
                            (client_code, client_name, resource_profile)
                        values (:code, :name, :profile)
                        """)
                .param("code", props.clientCode())
                .param("name", props.clientName())
                .param("profile", props.resourceProfile())
                .update();
        log.info("Экземпляр инициализирован: client_code={}, profile={}",
                props.clientCode(), props.resourceProfile());
    }

    private void initFirstAdmin() {
        Long users = jdbc.sql("select count(*) from md_users").query(Long.class).single();
        if (users > 0) {
            return;
        }
        require(props.adminLogin(), "dwh.instance.admin-login");
        require(props.adminEmail(), "dwh.instance.admin-email");
        require(props.adminPassword(), "dwh.instance.admin-password");

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
                        """)
                .param("userId", userId)
                .update();

        permissionService.recalculateEffectivePermissions(userId);
        // Пароль в лог не пишется никогда (FR-OBS-4); force_password_change=true —
        // первый вход потребует смену.
        log.info("Первый администратор создан: login={}, смена пароля при входе обязательна",
                props.adminLogin());
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Экземпляр не инициализирован: задайте " + property
                            + " в конфигурации развёртывания (FR-INST-1). "
                            + "Значения по умолчанию запрещены (AUDIT-03 C-1/C-2).");
        }
    }
}
