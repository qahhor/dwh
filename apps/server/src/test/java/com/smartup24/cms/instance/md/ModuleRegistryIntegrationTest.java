package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.repository.ModuleRegistryRepository;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class ModuleRegistryIntegrationTest {

    static JdbcClient jdbc;
    static ModuleRegistryService moduleService;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("dwh_module_test");
        jdbc = JdbcClient.create(ds);
        var mapper = new ObjectMapper();
        var repo = new ModuleRegistryRepository(jdbc, mapper);
        var auditRepo = new AuditLogRepository(jdbc, mapper);
        var auditService = new AuditLogService(auditRepo, null, new AuditDataRedactor());
        moduleService = new ModuleRegistryService(repo, auditService);
    }

    @Test
    @DisplayName("1. Реестр содержит базовые системные модули и эталонный модуль заметок")
    void registryContainsCoreAndReferenceModules() {
        var all = moduleService.getAllModules();
        assertThat(all).isNotEmpty();
        var codes = all.stream()
                .map(ModuleRegistryService.InstalledModuleView::code)
                .toList();
        assertThat(codes).contains("iam", "tasks", "files", "audit", "search", "notes");

        var active = moduleService.getActiveModules();
        assertThat(active).isNotEmpty();
    }

    @Test
    @DisplayName("2. Системные модули защищены от отключения")
    void systemModulesCannotBeDisabled() {
        assertThatThrownBy(() -> moduleService.toggleModuleStatus("iam", false))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.system_module_disable_forbidden")
                .hasFieldOrPropertyWithValue("params", Map.of("code", "iam"));

        assertThatThrownBy(() -> moduleService.toggleModuleStatus("tasks", false))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.system_module_disable_forbidden")
                .hasFieldOrPropertyWithValue("params", Map.of("code", "tasks"));
    }

    @Test
    @DisplayName("3. Прикладной модуль может быть отключен и повторно включен")
    void applicationModuleCanBeToggled() {
        var disabled = moduleService.toggleModuleStatus("notes", false);
        assertThat(disabled.status()).isEqualTo("DISABLED");

        var activeAfterDisable = moduleService.getActiveModules();
        assertThat(activeAfterDisable.stream().map(ModuleRegistryService.InstalledModuleView::code))
                .doesNotContain("notes");

        var enabled = moduleService.toggleModuleStatus("notes", true);
        assertThat(enabled.status()).isEqualTo("ACTIVE");

        var activeAfterEnable = moduleService.getActiveModules();
        assertThat(activeAfterEnable.stream().map(ModuleRegistryService.InstalledModuleView::code))
                .contains("notes");
    }

    @Test
    @DisplayName("4. Динамическая регистрация нового модуля через реестр")
    void registerNewCustomModule() {
        var registered = moduleService.registerModule(
                "inventory",
                "Управление складом",
                "Учет товаров и остатков",
                "1.0.0",
                "package",
                "/inventory",
                false,
                150,
                Map.of("currency", "USD"));

        assertThat(registered.code()).isEqualTo("inventory");
        assertThat(registered.status()).isEqualTo("ACTIVE");
        assertThat(registered.name()).isEqualTo("Управление складом");

        var found = moduleService.getModule("inventory");
        assertThat(found).isPresent();
        assertThat(found.get().attributes()).containsEntry("currency", "USD");
    }

    @Test
    @DisplayName("3.6: PUT registers a new module; a replace names its revision: 428 without, 409 from an older one")
    void putModuleReplacesFromItsRevision() {
        var created = moduleService.putModule(
                "warehouse", "Склад", null, "1.0.0", "package", "/warehouse", 160, Map.of(), null);
        assertThat(created.revision()).isEqualTo(1L);
        assertThat(created.status()).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> moduleService.putModule(
                        "warehouse", "Без ревизии", null, "1.0.1", "package", "/warehouse", 160, Map.of(), null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_REQUIRED));

        var replaced = moduleService.putModule(
                "warehouse", "Склад 2", null, "1.1.0", "package", "/warehouse", 160, Map.of(), 1L);
        assertThat(replaced.revision()).isEqualTo(2L);
        assertThat(replaced.name()).isEqualTo("Склад 2");

        assertThatThrownBy(() -> moduleService.putModule(
                        "warehouse", "Устаревшая", null, "1.0.2", "package", "/warehouse", 160, Map.of(), 1L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVISION_CONFLICT));
        assertThatThrownBy(() -> moduleService.putModule(
                        "nowhere", "Нет такого", null, "1.0.0", "box", "/nowhere", 1, Map.of(), 1L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        // A switch is a write of the row too: a replace made from the revision before it gets 409.
        moduleService.toggleModuleStatus("warehouse", false);
        assertThat(moduleService.getModule("warehouse").orElseThrow().revision())
                .isEqualTo(3L);
        assertThatThrownBy(() -> moduleService.putModule(
                        "warehouse", "Поверх", null, "1.2.0", "package", "/warehouse", 160, Map.of(), 2L))
                .isInstanceOf(ApiException.class);
        assertThat(moduleService.getModule("warehouse").orElseThrow().status())
                .as("a replace keeps the status")
                .isEqualTo("DISABLED");
    }
}
