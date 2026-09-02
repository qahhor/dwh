package com.greenwhite.dwh.instance.db;

import com.greenwhite.dwh.instance.config.bootstrap.InstanceBootstrap;
import com.greenwhite.dwh.instance.config.bootstrap.InstanceBootstrapProperties;
import com.greenwhite.dwh.instance.config.db.SchemaVersionGate;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.kauth.service.KauthPasswordHasher;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R4 (ремедиация, NFR-10 / FR-INST-1 / FR-INST-2, AUDIT-03 C-1/C-2):
 * 1) schema-gate не пускает приложение на пустой/несовпадающей схеме;
 * 2) миграции применяются только явным запуском (профиль migrate);
 * 3) bootstrap создаёт instance_info и первого админа из конфигурации,
 *    без параметров — отказывается, повторный запуск — идемпотентен;
 * 4) в справочном seed нет DEMO-данных и пользователей.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationGateAndBootstrapTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_gate_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private static DataSource dataSource() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    @Order(1)
    void gateRejectsEmptyDatabase() {
        SchemaVersionGate gate = new SchemaVersionGate(dataSource(), true);
        assertThatThrownBy(gate::verifySchemaMatchesApplication)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migrate");
    }

    @Test
    @Order(2)
    void gatePassesAfterExplicitMigration() {
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        new SchemaVersionGate(dataSource(), true).verifySchemaMatchesApplication();
    }

    @Test
    @Order(3)
    void seedContainsNoInstanceDataOrUsers() {
        JdbcClient jdbc = JdbcClient.create(dataSource());
        assertThat(jdbc.sql("select count(*) from md_instance_info").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from md_users").query(Long.class).single()).isZero();
        // Справочники при этом на месте
        assertThat(jdbc.sql("select count(*) from md_roles where pcode = 'admin'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    @Order(4)
    void bootstrapFailsWithoutConfiguration() {
        InstanceBootstrap bootstrap = bootstrap(new InstanceBootstrapProperties(
                null, null, null, null, null, null));
        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dwh.instance.client-code");
    }

    @Test
    @Order(5)
    void bootstrapCreatesInstanceAndAdmin_andIsIdempotent() throws Exception {
        InstanceBootstrap bootstrap = bootstrap(new InstanceBootstrapProperties(
                "client-042", "ООО Клиент", "M", "admin", "admin@client-042.uz", "S3cure-Pass-2026"));
        bootstrap.run(null);

        JdbcClient jdbc = JdbcClient.create(dataSource());
        assertThat(jdbc.sql("select client_code from md_instance_info").query(String.class).single())
                .isEqualTo("client-042");
        assertThat(jdbc.sql("select force_password_change from md_users where login = 'admin'")
                .query(Boolean.class).single())
                .as("первый вход обязан требовать смену пароля (AUDIT-03 C-1)")
                .isTrue();
        Long adminId = jdbc.sql("select id from md_users where login = 'admin'").query(Long.class).single();
        assertThat(jdbc.sql("select count(*) from md_effective_permissions where user_id = :id")
                .param("id", adminId).query(Long.class).single())
                .as("эффективные права админа материализованы")
                .isPositive();

        // Идемпотентность: повторный запуск ничего не дублирует
        bootstrap.run(null);
        assertThat(jdbc.sql("select count(*) from md_users").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from md_instance_info").query(Long.class).single()).isEqualTo(1);
    }

    private static InstanceBootstrap bootstrap(InstanceBootstrapProperties props) {
        JdbcClient jdbc = JdbcClient.create(dataSource());
        var permissionService = new MdPermissionService(new MdPermissionRepository(jdbc));
        return new InstanceBootstrap(jdbc, new KauthPasswordHasher(), permissionService, props);
    }
}
