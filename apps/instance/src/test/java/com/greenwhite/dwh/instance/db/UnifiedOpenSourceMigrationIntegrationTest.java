package com.greenwhite.dwh.instance.db;

import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class UnifiedOpenSourceMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("smartupcms_upgrade_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    @Test
    void migratesV018DataWithoutLossAndRemovesControlPlaneDependencies() {
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("018"))
                .load()
                .migrate();

        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        Long userId = jdbc.sql("""
                        insert into md_users (name, login, email)
                        values ('Upgrade User', 'upgrade-user', 'upgrade@example.test')
                        returning id
                        """)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into md_instance_info
                            (client_code, client_name, resource_profile, license_token, license_status, cp_public_keys)
                        values ('org-legacy', 'Legacy Organization', 'M', 'legacy-token', 'ACTIVE', '[{"kid":"legacy"}]')
                        """)
                .update();
        jdbc.sql("""
                        insert into ms_announcements_cache
                            (id, title_json, body_json, banner_type, published_at, state)
                        values (41, '{"ru":"Важно","en":"Important"}',
                                '{"ru":"Сохранить данные","en":"Keep data"}',
                                'warning', now(), 'published')
                        """)
                .update();
        jdbc.sql("insert into ms_announcement_reads (announcement_id, user_id) values (41, :userId)")
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        insert into md_custom_modules
                            (code, name, route_path, entrypoint_url, status, cp_ticket_id)
                        values ('legacy-module', 'Legacy Module', '/legacy', 'https://legacy.invalid/module.js',
                                'APPROVED', 'TICKET-MOD-LEGACY')
                        """)
                .update();

        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(columns(jdbc, "md_instance_info"))
                .doesNotContain("license_token", "license_status", "grace_until", "cp_public_keys");
        assertThat(columns(jdbc, "ms_announcements"))
                .contains("created_by", "created_at", "modified_at", "published_at", "archived_at", "lock_version");
        assertThat(jdbc.sql("select count(*) from ms_announcements").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from ms_announcement_reads where announcement_id = 41 and user_id = :userId")
                .param("userId", userId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select state from ms_announcements where id = 41").query(String.class).single())
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("select banner_type from ms_announcements where id = 41").query(String.class).single())
                .isEqualTo("WARNING");

        assertThat(jdbc.sql("select status from md_custom_modules where code = 'legacy-module'")
                .query(String.class).single()).isEqualTo("DISABLED");
        assertThat(columns(jdbc, "md_custom_modules")).doesNotContain("cp_ticket_id");
        assertThat(jdbc.sql("select legacy_approval_reference from md_custom_modules where code = 'legacy-module'")
                .query(String.class).single()).isEqualTo("TICKET-MOD-LEGACY");

        Long newAnnouncementId = jdbc.sql("""
                        insert into ms_announcements (title_json, body_json, banner_type)
                        values ('{"ru":"Черновик"}', '{"ru":"Локальное объявление"}', 'INFO')
                        returning id
                        """)
                .query(Long.class)
                .single();
        assertThat(newAnnouncementId).isGreaterThan(41L);

        assertThat(jdbc.sql("""
                        select action from md_form_actions
                        where form_code = 'platform.announcements'
                        order by action
                        """).query(String.class).list())
                .containsExactly("archive", "create", "publish", "update", "view");
        assertThat(jdbc.sql("""
                        select count(*)
                        from md_role_permissions rp
                        join md_roles r on r.id = rp.role_id
                        where r.pcode = 'admin' and rp.form_code = 'platform.announcements'
                        """).query(Long.class).single()).isEqualTo(5);
    }

    private static List<String> columns(JdbcClient jdbc, String tableName) {
        return jdbc.sql("""
                        select column_name
                        from information_schema.columns
                        where table_schema = 'public' and table_name = :tableName
                        order by ordinal_position
                        """)
                .param("tableName", tableName)
                .query(String.class)
                .list();
    }
}
