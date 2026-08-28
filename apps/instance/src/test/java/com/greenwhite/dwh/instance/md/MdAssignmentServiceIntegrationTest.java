package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.service.MdAssignmentService;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F2 (FR-PERM-4/5/10): назначение ролей и персональных прав.
 * Ключевые правила: пересчёт эффективных прав и рост версии при каждом
 * изменении (I-P2), защита последнего администратора (F-04),
 * запрет прав на пары вне каталога (FR-PERM-1).
 */
@Testcontainers
class MdAssignmentServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_assign_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MdAssignmentService service;
    static MdRoleRepository roleRepository;
    static MdPermissionService permissionService;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);

        var userRepository = new MdUserRepository(jdbc, new ObjectMapper());
        roleRepository = new MdRoleRepository(jdbc);
        var permissionRepository = new MdPermissionRepository(jdbc);
        permissionService = new MdPermissionService(permissionRepository);
        service = new MdAssignmentService(userRepository, roleRepository, permissionRepository, permissionService);
    }

    private static Long createUser(String login) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC',
                                '{}'::jsonb, false, false)
                        returning id
                        """)
                .param("login", login)
                .query(Long.class).single();
    }

    private static Long roleId(String pcode) {
        return roleRepository.findByPcode(pcode).orElseThrow().id();
    }

    @Test
    @DisplayName("Назначение роли материализует права с указанием источника и двигает версию")
    void assignRoleMaterializesPermissionsWithSource() {
        Long userId = createUser("assign_target");
        long before = permissionService.getPermissionVersion(userId);

        long after = service.assignRoles(userId, List.of(roleId("manager")));

        assertThat(after).as("версия обязана вырасти (I-P2)").isGreaterThan(before);

        var effective = service.getEffectivePermissions(userId);
        assertThat(effective).isNotEmpty();
        assertThat(effective).allSatisfy(i ->
                assertThat(i.source()).startsWith("role:"));
        assertThat(effective).anySatisfy(i -> {
            assertThat(i.formCode()).isEqualTo("tasks.items");
            assertThat(i.action()).isEqualTo("create");
        });
    }

    @Test
    @DisplayName("Персональное право видно как personal и живёт рядом с ролевыми")
    void personalPermissionIsDistinguishable() {
        Long userId = createUser("personal_target");
        service.assignRoles(userId, List.of(roleId("user")));

        service.replacePersonalPermissions(userId,
                List.of(new MdRoleRepository.PermissionPair("audit.log", "view")));

        var effective = service.getEffectivePermissions(userId);
        assertThat(effective)
                .filteredOn(i -> "personal".equals(i.source()))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.formCode()).isEqualTo("audit.log");
                    assertThat(i.action()).isEqualTo("view");
                });
        assertThat(effective).anySatisfy(i -> assertThat(i.source()).startsWith("role:"));
    }

    @Test
    @DisplayName("Замена набора прав — именно замена: прежние персональные права снимаются")
    void replaceSemanticsRemovesPrevious() {
        Long userId = createUser("replace_target");
        service.replacePersonalPermissions(userId,
                List.of(new MdRoleRepository.PermissionPair("audit.log", "view")));
        service.replacePersonalPermissions(userId, List.of());

        assertThat(service.getEffectivePermissions(userId))
                .filteredOn(i -> "personal".equals(i.source()))
                .isEmpty();
    }

    @Test
    @DisplayName("FR-PERM-1: право на пару вне каталога не выдаётся")
    void rejectsPermissionOutsideCatalog() {
        Long userId = createUser("bad_perm_target");

        assertThatThrownBy(() -> service.replacePersonalPermissions(userId,
                List.of(new MdRoleRepository.PermissionPair("no.such.form", "view"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no.such.form");
    }

    @Test
    @DisplayName("F-04: роль admin нельзя снять с последнего администратора")
    void lastAdminIsProtected() {
        Long onlyAdmin = createUser("the_only_admin");
        service.assignRoles(onlyAdmin, List.of(roleId("admin")));

        assertThatThrownBy(() -> service.assignRoles(onlyAdmin, List.of(roleId("user"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("последнего администратора");

        // Права не пострадали: пользователь остался администратором
        assertThat(service.getUserRoleIds(onlyAdmin)).contains(roleId("admin"));
    }

    @Test
    @DisplayName("Со вторым администратором снятие роли у первого разрешено")
    void adminCanBeRemovedWhenAnotherExists() {
        Long first = createUser("admin_one");
        Long second = createUser("admin_two");
        service.assignRoles(first, List.of(roleId("admin")));
        service.assignRoles(second, List.of(roleId("admin")));

        service.assignRoles(first, List.of(roleId("user")));

        assertThat(service.getUserRoleIds(first)).doesNotContain(roleId("admin"));
        assertThat(service.getUserRoleIds(second)).contains(roleId("admin"));
    }
}
