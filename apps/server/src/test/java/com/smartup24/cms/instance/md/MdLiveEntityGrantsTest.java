package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Live form registration joins the same transaction as grants and permission versions (ADR-0038). */
class MdLiveEntityGrantsTest {
    private static HikariDataSource pool;
    private static JdbcClient jdbc;
    private static MdPermissionRepository repository;
    private static MdPermissionService service;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void setup() {
        pool = (HikariDataSource) TestDatabases.migratedCopy("live_grants");
        jdbc = JdbcClient.create(pool);
        repository = new MdPermissionRepository(jdbc);
        service = new MdPermissionService(repository);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
        jdbc.sql("revoke create on schema public from public").update();
        jdbc.sql("create role stu05_live_catalog_dml nologin").update();
        jdbc.sql("grant usage on schema public to stu05_live_catalog_dml").update();
        jdbc.sql("grant select, insert, update, delete on all tables in schema public to stu05_live_catalog_dml")
                .update();
        jdbc.sql("grant usage, select on all sequences in schema public to stu05_live_catalog_dml")
                .update();
    }

    @AfterAll
    static void close() {
        pool.close();
    }

    @Test
    void onlyAdministrativeRolesGainAccessAndTheirVersionsAdvance() {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            Map<String, Long> users = users("grant");
            service.registerEntityDefinition(MdLiveEntityCatalogTest.entity(), MdLiveEntityCatalogTest.RU);

            assertThat(jdbc.sql("""
                    select distinct r.pcode from md_role_permissions rp
                    join md_roles r on r.id = rp.role_id where rp.form_code = 'studio.alpha'
                    """).query(String.class).set()).containsExactlyInAnyOrder("admin", "chief_admin");
            for (String role : Set.of("admin", "chief_admin")) {
                assertThat(repository.getEffectivePermissionsForUser(users.get(role)))
                        .contains(
                                "studio.alpha.view",
                                "studio.alpha.create",
                                "studio.alpha.update",
                                "studio.alpha.delete");
                assertThat(repository.getPermissionVersion(users.get(role))).isEqualTo(2);
            }
            for (String role : Set.of("user", "auditor")) {
                assertThat(repository.getEffectivePermissionsForUser(users.get(role)))
                        .noneMatch(permission -> permission.startsWith("studio.alpha."));
                assertThat(repository.getPermissionVersion(users.get(role))).isEqualTo(1);
            }
            assertThat(repository.getAllFormsWithActions())
                    .filteredOn(row -> row.formCode().equals("studio.alpha"))
                    .hasSize(4)
                    .allMatch(row -> !row.isDeprecated() && row.formName().equals("Alpha"));
        });
    }

    @Test
    void failureRollsBackCatalogGrantsAndPermissionVersionsTogether() {
        Map<String, Long> users = transaction.execute(status -> users("rollback"));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    service.registerEntityDefinition(MdLiveEntityCatalogTest.entity(), MdLiveEntityCatalogTest.RU);
                    assertThat(repository.getPermissionVersion(users.get("admin")))
                            .isEqualTo(2);
                    throw new IllegalStateException("abort activation");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("abort activation");

        assertThat(jdbc.sql("select count(*) from md_forms where code = 'studio.alpha'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from md_role_permissions where form_code = 'studio.alpha'")
                        .query(Long.class)
                        .single())
                .isZero();
        for (Long user : users.values()) {
            assertThat(repository.getPermissionVersion(user)).isEqualTo(1);
            assertThat(repository.getEffectivePermissionsForUser(user))
                    .noneMatch(permission -> permission.startsWith("studio.alpha."));
        }
    }

    private static Map<String, Long> users(String prefix) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String role : Set.of("admin", "chief_admin", "user", "auditor")) {
            String login = prefix + "_" + role;
            long user = jdbc.sql("""
                    insert into md_users(name,login,email,password_hash,state,language,timezone,
                        attributes,is_2fa_enabled,force_password_change)
                    values(:login,:login,:email,'x','A','ru','UTC','{}',false,false) returning id
                    """)
                    .param("login", login)
                    .param("email", login + "@test.local")
                    .query(Long.class)
                    .single();
            jdbc.sql("insert into md_user_roles(user_id,role_id) select :user,id from md_roles where pcode=:role")
                    .param("user", user)
                    .param("role", role)
                    .update();
            repository.recalculateEffectivePermissions(user);
            result.put(role, user);
        }
        return result;
    }

    @Test
    void registrationWorksWithDmlPermissionsAndNoSchemaDdl() {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            Map<String, Long> users = users("dml");
            jdbc.sql("set local role stu05_live_catalog_dml").update();
            assertThat(jdbc.sql("select has_schema_privilege(current_user, 'public', 'CREATE')")
                            .query(Boolean.class)
                            .single())
                    .isFalse();
            assertThat(jdbc.sql(
                                    "select rolsuper or rolcreatedb or rolcreaterole from pg_roles where rolname=current_user")
                            .query(Boolean.class)
                            .single())
                    .isFalse();

            service.registerEntityDefinition(MdLiveEntityCatalogTest.entity(), MdLiveEntityCatalogTest.RU);

            assertThat(repository.getEffectivePermissionsForUser(users.get("admin")))
                    .contains("studio.alpha.view", "studio.alpha.create", "studio.alpha.update", "studio.alpha.delete");
            assertThat(repository.getPermissionVersion(users.get("admin"))).isEqualTo(2);
            assertThatThrownBy(() -> jdbc.sql("create table stu05_ddl_must_fail(id bigint)")
                            .update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
        });
    }
}
