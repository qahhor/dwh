package com.smartup24.cms.instance.a1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.smartup24.cms.instance.kauth.repository.SsoProviderRepository;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The instance roles from V110 for the A1 integration on the CMS: the roles themselves, the effective permissions
 * of analyst and the OneID provider row.
 */
class A1InstanceRolesTest extends EmbeddedPostgresTest {

    /** The exact analyst set from V110, part 3: the set of the user role without tasks.*. */
    static final List<String> ANALYST_PAIRS = List.of(
            "md.profile:view",
            "md.profile:update",
            "md.profile:manage_tokens",
            "md.profile:manage_channels",
            "mf.files:view",
            "mf.files:upload",
            "search:view",
            "notify.inbox:view",
            "notify.preferences:view",
            "notify.preferences:update",
            "notify.announcements:view");

    /**
     * Modules add their working pairs to analyst in their own migration (V112: upl.sources, V115: upl.packages);
     * V116 grants upl.packages:apply to chief_admin and admin only.
     */
    static final List<String> LATER_MODULE_ANALYST_PAIRS =
            List.of("upl.sources:view", "upl.packages:view", "upl.packages:upload");

    private static List<String> allAnalystPairs() {
        return Stream.concat(ANALYST_PAIRS.stream(), LATER_MODULE_ANALYST_PAIRS.stream())
                .toList();
    }

    private static final String TEST_ANALYST_LOGIN = "test-analyst-a1";

    /**
     * A full name in Uzbek Cyrillic with its own letters and a Latin apostrophe: catches an encoding skew between
     * JDBC and the database, and broken escaping.
     */
    private static final String TEST_ANALYST_NAME = "TEST Ўринбоева Ҳуррият Ғайрат қизи (G'ijduvon)";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdUserService userService;

    @Autowired
    private SsoProviderRepository ssoProviders;

    @Autowired
    private TransactionTemplate tx;

    @AfterEach
    void removeTestAnalyst() {
        // Cascades to md_user_roles / md_effective_permissions / md_user_permission_versions (V001)
        jdbc.sql("delete from md_users where login = :login")
                .param("login", TEST_ANALYST_LOGIN)
                .update();
    }

    @Test
    @DisplayName("AC-9: роли экземпляра chief_admin и analyst заведены с именами из V110")
    void instanceRolesExistWithPcode() {
        List<String> pcodes = jdbc.sql(
                        "select pcode from md_roles where pcode in ('chief_admin','analyst') order by pcode")
                .query(String.class)
                .list();
        assertThat(pcodes).containsExactly("analyst", "chief_admin");

        String chiefAdminName = jdbc.sql("select name from md_roles where pcode = 'chief_admin'")
                .query(String.class)
                .single();
        assertThat(chiefAdminName).isEqualTo("Bosh administrator");

        String analystName = jdbc.sql("select name from md_roles where pcode = 'analyst'")
                .query(String.class)
                .single();
        assertThat(analystName).isEqualTo("Tahlilchi");

        List<Map<String, Object>> rows = jdbc.sql("""
                select pcode, state, order_no from md_roles
                where pcode in ('chief_admin','analyst') order by pcode
                """).query().listOfRows();
        assertThat(rows)
                .extracting(r -> r.get("pcode"), r -> r.get("state"), r -> r.get("order_no"))
                .containsExactly(tuple("analyst", "A", 120), tuple("chief_admin", "A", 110));
    }

    @Test
    @DisplayName("AC-9: chief_admin покрывает весь каталог пар форма.действие")
    void chiefAdminCoversWholeCatalog() {
        Long chiefAdminPairs = jdbc.sql("""
                select count(*) from md_role_permissions p
                join md_roles r on r.id = p.role_id
                where r.pcode = 'chief_admin'
                """).query(Long.class).single();
        Long catalogPairs = jdbc.sql("select count(*) from md_form_actions")
                .query(Long.class)
                .single();

        assertThat(catalogPairs).isPositive();
        assertThat(chiefAdminPairs).isEqualTo(catalogPairs);
    }

    @Test
    @DisplayName("AC-9: у analyst нет админских форм и задачника")
    void analystHasNoAdminForms() {
        Long forbidden = jdbc.sql("""
                select count(*) from md_role_permissions p
                join md_roles r on r.id = p.role_id
                where r.pcode = 'analyst'
                  and (p.form_code in ('md.users', 'md.roles', 'md.assignments', 'audit.log',
                                       'md.settings', 'md.custom_fields', 'webhook.subscriptions')
                       or p.form_code like 'tasks.%')
                """).query(Long.class).single();
        assertThat(forbidden).isZero();

        List<String> analystPairs = jdbc.sql("""
                select p.form_code || ':' || p.action from md_role_permissions p
                join md_roles r on r.id = p.role_id
                where r.pcode = 'analyst'
                """).query(String.class).list();
        assertThat(analystPairs).containsExactlyInAnyOrderElementsOf(allAnalystPairs());
    }

    @Test
    @DisplayName("И6: право «Применение» загрузки есть у chief_admin и admin, у analyst нет")
    void uploadApplyRightOnlyForAdmins() {
        List<String> holders = jdbc.sql("""
                select r.pcode from md_role_permissions p
                join md_roles r on r.id = p.role_id
                where p.form_code = 'upl.packages' and p.action = 'apply'
                order by r.pcode
                """).query(String.class).list();
        assertThat(holders).contains("admin", "chief_admin").doesNotContain("analyst");
    }

    @Test
    @DisplayName("AC-9: системные роли каркаса не тронуты")
    void systemRolesUntouched() {
        List<String> pcodes = jdbc.sql("""
                select pcode from md_roles
                where pcode in ('admin', 'manager', 'auditor', 'user')
                order by pcode
                """).query(String.class).list();
        assertThat(pcodes).containsExactly("admin", "auditor", "manager", "user");

        Long adminPairs = jdbc.sql("""
                select count(*) from md_role_permissions p
                join md_roles r on r.id = p.role_id
                where r.pcode = 'admin'
                """).query(Long.class).single();
        Long catalogPairs = jdbc.sql("select count(*) from md_form_actions")
                .query(Long.class)
                .single();
        assertThat(adminPairs).isEqualTo(catalogPairs);
    }

    @Test
    @DisplayName("AC-14: пользователь, созданный сразу с ролью analyst, имеет ровно одну роль и только права analyst")
    void analystUserHasExactlyOneRoleAndOnlyAnalystEffectivePermissions() {
        Long systemId = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        Long analystRoleId = jdbc.sql("select id from md_roles where pcode = 'analyst'")
                .query(Long.class)
                .single();

        var user = userService.createUser(
                TEST_ANALYST_NAME,
                TEST_ANALYST_LOGIN,
                TEST_ANALYST_LOGIN + "@test.local",
                null,
                "StrongPassword2026!",
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                List.of(analystRoleId),
                systemId);

        // The framework grants the user role only when the role list is empty, so it must not be here
        List<Long> roleIds = jdbc.sql("select role_id from md_user_roles where user_id = :id")
                .param("id", user.id())
                .query(Long.class)
                .list();
        assertThat(roleIds).containsExactly(analystRoleId);

        // The Cyrillic full name passed through JDBC and the database unchanged: the string and its bytes are compared
        Map<String, Object> stored = jdbc.sql("select name, octet_length(name) as bytes from md_users where id = :id")
                .param("id", user.id())
                .query()
                .singleRow();
        assertThat(stored.get("name")).isEqualTo(TEST_ANALYST_NAME);
        assertThat(((Number) stored.get("bytes")).intValue())
                .isEqualTo(TEST_ANALYST_NAME.getBytes(StandardCharsets.UTF_8).length);

        // The framework materialized the effective permissions at creation (scopeService.recalculateFor)
        List<String> effective = jdbc.sql(
                        "select form_code || ':' || action from md_effective_permissions where user_id = :id")
                .param("id", user.id())
                .query(String.class)
                .list();
        assertThat(effective).containsExactlyInAnyOrderElementsOf(allAnalystPairs());
        assertThat(effective)
                .noneMatch(p -> p.startsWith("md.users:")
                        || p.startsWith("rbac.")
                        || p.startsWith("audit.log:")
                        || p.startsWith("md.settings:")
                        || p.startsWith("tasks."));
    }

    @Test
    @DisplayName("AC-9: строка OneID заведена и выключена")
    void oneIdSeededDisabled() {
        List<Map<String, Object>> rows = jdbc.sql("""
                select is_enabled, auto_provision, client_secret
                from md_sso_providers where provider_id = 'oneid'
                """).query().listOfRows();

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("is_enabled")).isEqualTo(false);
        assertThat(row.get("auto_provision")).isEqualTo(false);
        assertThat(row.get("client_secret")).isNull();
    }

    @Test
    @DisplayName("AC-16: oneid не попадает в список включённых провайдеров — кнопки OneID не будет")
    void oneIdNotAmongEnabledProviders() {
        assertThat(ssoProviders.findEnabledProviders()).noneMatch(p -> "oneid".equals(p.providerId()));
        assertThat(ssoProviders.findByProviderId("oneid")).isEmpty();
    }

    @Test
    @DisplayName("AC-9: повторный прогон V110 достраивает частичное состояние и ничего не дублирует")
    void v110IsIdempotent() throws IOException {
        String script = new ClassPathResource("db/migration/V110__a1_instance_roles.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        long rolesBefore = count("md_roles");
        long permissionsBefore = count("md_role_permissions");
        long providersBefore = count("md_sso_providers");

        // Everything runs in one rolled-back transaction: the shared test database stays clean whatever the outcome,
        // and the rollback undoes the script's set lock_timeout/statement_timeout (PostgreSQL rolls back a plain SET)
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();

            // Partial state (a previous run was cut off): the role exists, the analyst permissions do not
            jdbc.sql("""
                    delete from md_role_permissions
                    where role_id = (select id from md_roles where pcode = 'analyst')
                      and form_code || ':' || action in (:pairs)
                    """).param("pairs", ANALYST_PAIRS).update();
            assertThat(count("md_role_permissions")).isEqualTo(permissionsBefore - ANALYST_PAIRS.size());

            jdbc.sql(script).update();
            assertThat(count("md_roles")).isEqualTo(rolesBefore);
            assertThat(count("md_role_permissions")).isEqualTo(permissionsBefore);
            assertThat(count("md_sso_providers")).isEqualTo(providersBefore);

            // The second repeat adds nothing
            jdbc.sql(script).update();
            assertThat(count("md_role_permissions")).isEqualTo(permissionsBefore);
        });

        // After the rollback the analyst permissions are in place. There is no show lock_timeout/statement_timeout
        // check: outside the transaction the pool may hand out another connection, and PostgreSQL itself resets SET
        // on rollback.
        assertThat(count("md_role_permissions")).isEqualTo(permissionsBefore);
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }
}
