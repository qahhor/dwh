package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
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
    static com.greenwhite.dwh.instance.audit.service.AuditLogService auditLogService;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);

        var userRepository = new MdUserRepository(jdbc, new ObjectMapper());
        roleRepository = new MdRoleRepository(jdbc);
        var permissionRepository = new MdPermissionRepository(jdbc);
        permissionService = new MdPermissionService(permissionRepository);
        auditLogService = new com.greenwhite.dwh.instance.audit.service.AuditLogService(
                new com.greenwhite.dwh.instance.audit.repository.AuditLogRepository(jdbc, new ObjectMapper()), null,
                new com.greenwhite.dwh.instance.audit.service.AuditDataRedactor());
        service = new MdAssignmentService(userRepository, roleRepository, permissionRepository,
                permissionService, auditLogService);
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

    /**
     * FR-AUD-1: выдача доступа обязана оставлять след. До этой правки изменение
     * ролей и персональных прав не писалось в аудит вовсе — восстановить
     * «кто кому выдал право» было нечем.
     */
    @Test
    @DisplayName("Назначение ролей пишется в аудит с диффом granted/revoked")
    void roleAssignmentIsAudited() {
        Long userId = createUser("audited_roles");

        service.assignRoles(userId, List.of(roleId("manager")));
        service.assignRoles(userId, List.of(roleId("user")));

        var rows = auditRows("md_user_roles", userId);
        assertThat(rows).as("две операции — две записи").hasSize(2);

        assertThat(rows.get(0)).as("в журнале имя роли, а не служебный код").contains("Менеджер").contains("granted");
        assertThat(rows.get(1)).as("снятие роли видно как revoked").contains("revoked").contains("Менеджер");
        assertThat(rows.get(1)).contains("Пользователь");
    }

    @Test
    @DisplayName("Персональные права пишутся в аудит отдельной строкой")
    void personalPermissionChangeIsAudited() {
        Long userId = createUser("audited_perms");

        service.replacePersonalPermissions(userId,
                List.of(new MdRoleRepository.PermissionPair("audit.log", "view")));
        service.replacePersonalPermissions(userId, List.of());

        var rows = auditRows("md_user_permissions", userId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).contains("audit.log.view").contains("granted");
        assertThat(rows.get(1)).contains("revoked").contains("audit.log.view");
    }

    /** new_row как текст: проверяем факт записи и содержимое диффа, а не форму сериализации. */
    private static List<String> auditRows(String tableName, Long rowPk) {
        return jdbc.sql("""
                        select coalesce(old_row::text, '') || ' ' || coalesce(new_row::text, '')
                        from audit_log
                        where table_name = :t and row_pk = :pk
                        order by id
                        """)
                .param("t", tableName)
                .param("pk", String.valueOf(rowPk))
                .query(String.class).list();
    }
}
