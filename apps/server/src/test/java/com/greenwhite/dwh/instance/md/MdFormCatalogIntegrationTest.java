package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdScopeRepository;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdRoleService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-PERM-1 (дефект Д-5): каталог форм приводится в соответствие с кодом.
 *
 * До этой работы каталог наполнялся только миграциями, а метод регистрации из
 * кода не вызывался ниоткуда. На живом стенде это дало четыре пары, за которыми
 * нет ни одного эндпоинта: notify.preferences.view / .update,
 * iam.profile.manage_channels, platform.files.manage_quotas. Администратор
 * видел их в матрице прав и мог выдать — право не открывало ничего.
 */
@Testcontainers
class MdFormCatalogIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_catalog_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MdPermissionService permissionService;
    static MdRoleService roleService;
    static MdRoleRepository roleRepository;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);

        permissionService = new MdPermissionService(new MdPermissionRepository(jdbc));
        roleRepository = new MdRoleRepository(jdbc);
        roleService = new MdRoleService(roleRepository, permissionService,
                new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null,
                        new AuditDataRedactor()),
                new MdScopeRepository(jdbc));
    }

    @Test
    @DisplayName("Пара из кода, которой не было в каталоге, появляется живой")
    void newPairFromCodeIsRegistered() {
        permissionService.syncFormCatalog(withRealPairs("reports.sales.view"));

        assertThat(permissionService.getGrantablePairs()).contains("reports.sales.view");
        assertThat(isDeprecated("reports.sales", "view")).isFalse();
    }

    @Test
    @DisplayName("Запись каталога без эндпоинта помечается устаревшей, но не удаляется")
    void catalogEntryWithoutEndpointBecomesDeprecated() {
        // Миграции засеяли notify.preferences, но эндпоинта под неё в коде нет.
        assertThat(countActions("notify.preferences")).isEqualTo(2);

        permissionService.syncFormCatalog(realPairs());

        assertThat(isDeprecated("notify.preferences", "view"))
                .as("мёртвая пара обязана быть помечена").isTrue();
        assertThat(countActions("notify.preferences"))
                .as("удалять нельзя: каскад снял бы уже выданные права").isEqualTo(2);
        assertThat(permissionService.getGrantablePairs()).doesNotContain("notify.preferences.view");
    }

    @Test
    @DisplayName("Устаревшую пару нельзя выдать роли")
    void deprecatedPairCannotBeGranted() {
        permissionService.syncFormCatalog(realPairs());
        var role = roleRepository.create("Роль для устаревшего права", null, "A", 100);

        assertThatThrownBy(() -> roleService.setRolePermissions(role.id(),
                List.of(new MdRoleRepository.PermissionPair("notify.preferences", "view"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("недоступна для выдачи");
    }

    @Test
    @DisplayName("Вернувшийся в код эндпоинт снимает пометку устаревшего")
    void reappearingPairIsRevived() {
        permissionService.syncFormCatalog(realPairs());
        assertThat(isDeprecated("notify.preferences", "view")).isTrue();

        permissionService.syncFormCatalog(withRealPairs("notify.preferences.view"));

        assertThat(isDeprecated("notify.preferences", "view")).isFalse();
        assertThat(permissionService.getGrantablePairs()).contains("notify.preferences.view");
    }

    @Test
    @DisplayName("Каталог получает человеческие имена, а не коды")
    void catalogCarriesHumanNames() {
        permissionService.syncFormCatalog(realPairs());

        assertThat(permissionService.getFormCatalog())
                .filteredOn(item -> "iam.users".equals(item.formCode()) && "block".equals(item.action()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.formName()).isEqualTo("Пользователи");
                    assertThat(item.actionName()).isEqualTo("Блокировка");
                    assertThat(item.isDeprecated()).isFalse();
                });
    }

    /** Пары, реально объявленные аннотациями в коде приложения. */
    private static Set<String> realPairs() {
        return MdFormCatalogTest.declaredPairsFromSources();
    }

    private static Set<String> withRealPairs(String extra) {
        var pairs = new java.util.TreeSet<>(realPairs());
        pairs.add(extra);
        return pairs;
    }

    private static boolean isDeprecated(String formCode, String action) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select is_deprecated from md_form_actions
                        where form_code = :form and action = :action
                        """)
                .param("form", formCode)
                .param("action", action)
                .query(Boolean.class).single());
    }

    private static long countActions(String formCode) {
        return jdbc.sql("select count(*) from md_form_actions where form_code = :form")
                .param("form", formCode)
                .query(Long.class).single();
    }
}
