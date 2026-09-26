package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user list reads every row's roles in one query. That query was built but never run, so rows had no
 * roles, and editing a user opened from the list sent an empty role list and removed all of their roles.
 */
@Testcontainers
class MdRoleRepositoryUserRolesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_user_roles_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MdRoleRepository roles;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        roles = new MdRoleRepository(jdbc);
    }

    @Test
    @DisplayName("Roles of many users are read in one call, and a user without roles gets an empty list")
    void readsTheRolesOfEveryUser() {
        Long admin = roles.findByPcode("admin").orElseThrow().id();
        Long withRole = createUser("roles_with");
        Long withoutRole = createUser("roles_without");
        roles.assignRolesToUser(withRole, List.of(admin));

        var byUser = roles.getUsersRoleIds(List.of(withRole, withoutRole));

        assertThat(byUser.get(withRole)).containsExactly(admin);
        assertThat(byUser.get(withoutRole)).isEmpty();
    }

    private static Long createUser(String login) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC', '{}'::jsonb, false, false)
                        returning id
                        """)
                .param("login", login)
                .query(Long.class).single();
    }
}
