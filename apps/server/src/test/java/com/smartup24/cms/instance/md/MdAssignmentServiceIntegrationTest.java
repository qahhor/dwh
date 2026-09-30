package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.GrantDto;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.PermissionsVersionResponse;
import com.smartup24.cms.instance.md.repository.MdOrgUnitRepository;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.md.service.MdAssignmentService;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * F2 (FR-PERM-4/5/10): назначение ролей и персональных прав.
 * Ключевые правила: пересчёт эффективных прав и рост версии при каждом
 * изменении (I-P2), защита последнего администратора (F-04),
 * запрет прав на пары вне каталога (FR-PERM-1).
 */
class MdAssignmentServiceIntegrationTest {

    static JdbcClient jdbc;
    static MdAssignmentService service;
    static MdRoleRepository roleRepository;
    static MdPermissionService permissionService;
    static MdScopeService scopeService;
    static MdScopeRepository scopeRepository;
    static Long scopeUnitId;
    static AuditLogService auditLogService;
    static MdUserRepository userRepository;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("dwh_assign_test");
        jdbc = JdbcClient.create(ds);

        userRepository = new MdUserRepository(jdbc, new ObjectMapper());
        roleRepository = new MdRoleRepository(jdbc);
        var permissionRepository = new MdPermissionRepository(jdbc);
        permissionService = new MdPermissionService(permissionRepository);
        auditLogService =
                new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null, new AuditDataRedactor());
        scopeRepository = new MdScopeRepository(jdbc);
        scopeService =
                new MdScopeService(scopeRepository, new MdOrgUnitRepository(jdbc), permissionService, auditLogService);
        service = new MdAssignmentService(
                userRepository, roleRepository, permissionRepository, permissionService, scopeService, auditLogService);
        scopeUnitId = jdbc.sql("""
                        insert into md_org_units (parent_id, code, name, kind, state, order_no)
                        values (null, 'ASSIGN-HQ', 'Assignment HQ', 'company', 'A', 10)
                        returning id
                        """).query(Long.class).single();
    }

    private static Long createUser(String login) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC',
                                '{}'::jsonb, false, false)
                        returning id
                        """).param("login", login).query(Long.class).single();
    }

    /** The change made from the user's current revision, as the screen that just read the user sends it. */
    private static PermissionsVersionResponse assignRoles(Long userId, List<Long> roleIds) {
        return service.assignRoles(userId, roleIds, revisionOf(userId));
    }

    private static PermissionsVersionResponse replacePersonalPermissions(Long userId, List<GrantDto> grants) {
        return service.replacePersonalPermissions(userId, grants, revisionOf(userId));
    }

    private static long revisionOf(Long userId) {
        return userRepository.findById(userId).orElseThrow().revision();
    }

    private static Long roleId(String pcode) {
        return roleRepository.findByPcode(pcode).orElseThrow().id();
    }

    @Test
    @DisplayName("Назначение роли материализует права с указанием источника и двигает версию")
    void assignRoleMaterializesPermissionsWithSource() {
        Long userId = createUser("assign_target");
        long before = permissionService.getPermissionVersion(userId);

        long after = assignRoles(userId, List.of(roleId("manager"))).permissionsVersion();

        assertThat(after).as("версия обязана вырасти (I-P2)").isGreaterThan(before);

        var effective = service.getEffectivePermissions(userId);
        assertThat(effective).isNotEmpty();
        assertThat(effective).allSatisfy(i -> assertThat(i.source()).startsWith("role:"));
        assertThat(effective).anySatisfy(i -> {
            assertThat(i.form()).isEqualTo("tasks.items");
            assertThat(i.action()).isEqualTo("create");
        });
    }

    @Test
    @DisplayName("Персональное право видно как personal и живёт рядом с ролевыми")
    void personalPermissionIsDistinguishable() {
        Long userId = createUser("personal_target");
        assignRoles(userId, List.of(roleId("user")));

        replacePersonalPermissions(userId, List.of(new GrantDto("audit.log", "view")));

        var effective = service.getEffectivePermissions(userId);
        assertThat(effective)
                .filteredOn(i -> "personal".equals(i.source()))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.form()).isEqualTo("audit.log");
                    assertThat(i.action()).isEqualTo("view");
                });
        assertThat(effective).anySatisfy(i -> assertThat(i.source()).startsWith("role:"));
    }

    @Test
    @DisplayName("Замена набора прав — именно замена: прежние персональные права снимаются")
    void replaceSemanticsRemovesPrevious() {
        Long userId = createUser("replace_target");
        replacePersonalPermissions(userId, List.of(new GrantDto("audit.log", "view")));
        replacePersonalPermissions(userId, List.of());

        assertThat(service.getEffectivePermissions(userId))
                .filteredOn(i -> "personal".equals(i.source()))
                .isEmpty();
    }

    @Test
    @DisplayName("FR-PERM-1: право на пару вне каталога не выдаётся")
    void rejectsPermissionOutsideCatalog() {
        Long userId = createUser("bad_perm_target");

        assertThatThrownBy(() -> replacePersonalPermissions(userId, List.of(new GrantDto("no.such.form", "view"))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.permission_not_grantable")
                .hasFieldOrPropertyWithValue("params", Map.of("permission", "no.such.form.view"));
    }

    @Test
    @DisplayName("3.6: roles and personal rights are saved from the user's revision and raise it")
    void rolesAndRightsAreSavedFromTheUsersRevision() {
        Long userId = createUser("revision_target");
        long read = revisionOf(userId);

        PermissionsVersionResponse roles = service.assignRoles(userId, List.of(roleId("user")), read);
        assertThat(roles.revision()).isEqualTo(read + 1).isEqualTo(revisionOf(userId));

        assertThatThrownBy(() ->
                        service.replacePersonalPermissions(userId, List.of(new GrantDto("audit.log", "view")), read))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.common.revision_conflict");
        assertThatThrownBy(() -> service.assignRoles(userId, List.of(roleId("manager")), read))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.common.revision_conflict");
        assertThat(service.getUserRoleIds(userId)).containsExactly(roleId("user"));

        PermissionsVersionResponse rights = service.replacePersonalPermissions(
                userId, List.of(new GrantDto("audit.log", "view")), roles.revision());
        assertThat(rights.revision()).isEqualTo(roles.revision() + 1);
    }

    @Test
    @DisplayName("F-04: роль admin нельзя снять с последнего администратора")
    void lastAdminIsProtected() {
        Long onlyAdmin = createUser("the_only_admin");
        assignRoles(onlyAdmin, List.of(roleId("admin")));

        assertThatThrownBy(() -> assignRoles(onlyAdmin, List.of(roleId("user"))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.last_admin_role");

        // Права не пострадали: пользователь остался администратором
        assertThat(service.getUserRoleIds(onlyAdmin)).contains(roleId("admin"));
    }

    @Test
    @DisplayName("Со вторым администратором снятие роли у первого разрешено")
    void adminCanBeRemovedWhenAnotherExists() {
        Long first = createUser("admin_one");
        Long second = createUser("admin_two");
        assignRoles(first, List.of(roleId("admin")));
        assignRoles(second, List.of(roleId("admin")));

        assignRoles(first, List.of(roleId("user")));

        assertThat(service.getUserRoleIds(first)).doesNotContain(roleId("admin"));
        assertThat(service.getUserRoleIds(second)).contains(roleId("admin"));
    }

    @Test
    @DisplayName("Замена роли атомарно заменяет и effective data scope")
    void roleReplacementRecalculatesDataScopeInSameTransaction() {
        Long userId = createUser("scope_role_replace");
        jdbc.sql("insert into md_user_org_units (user_id, org_unit_id) values (:userId, :unitId)")
                .param("userId", userId)
                .param("unitId", scopeUnitId)
                .update();
        Long narrowRole =
                roleRepository.create("Scoped unit role", null, "A", 100).id();
        scopeRepository.setRoleRule(narrowRole, MdScopeService.RULE_UNITS);

        assignRoles(userId, List.of(roleId("user")));
        assertThat(scopeService.getUserScope(userId).rule()).isEqualTo(MdScopeService.RULE_ALL);

        assignRoles(userId, List.of(narrowRole));

        assertThat(scopeService.getUserScope(userId).rule()).isEqualTo(MdScopeService.RULE_UNITS);
        assertThat(scopeService.getUserScope(userId).visibleOrgUnitIds()).containsExactly(scopeUnitId);
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

        assignRoles(userId, List.of(roleId("manager")));
        assignRoles(userId, List.of(roleId("user")));

        var rows = auditRows("md_user_roles", userId);
        assertThat(rows).as("две операции — две записи").hasSize(2);

        assertThat(rows.get(0))
                .as("в журнале имя роли, а не служебный код")
                .contains("Менеджер")
                .contains("granted");
        assertThat(rows.get(1))
                .as("снятие роли видно как revoked")
                .contains("revoked")
                .contains("Менеджер");
        assertThat(rows.get(1)).contains("Пользователь");
    }

    @Test
    @DisplayName("Персональные права пишутся в аудит отдельной строкой")
    void personalPermissionChangeIsAudited() {
        Long userId = createUser("audited_perms");

        replacePersonalPermissions(userId, List.of(new GrantDto("audit.log", "view")));
        replacePersonalPermissions(userId, List.of());

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
                .query(String.class)
                .list();
    }
}
