package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.module.ModuleManifest;
import com.smartup24.cms.instance.common.module.ModuleManifests;
import com.smartup24.cms.instance.md.repository.ModuleRegistryRepository;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.PlatformVersion;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class ModuleRegistryIntegrationTest {

    /** The modules V028, V113 and V182 register. */
    private static final List<String> MIGRATED =
            List.of("iam", "tasks", "files", "audit", "search", "notes", "upl", "example");

    static JdbcClient jdbc;
    static ModuleRegistryService moduleService;
    static ModuleCatalog catalog;
    static ModuleManifest newModule;
    static ModuleRegistryService withNewModule;
    static MdI18nCatalog libraryTexts;

    /** The messages of the test's library module: its name and description in ru, uz and en (ADR-0033, 6.2). */
    private static final String LIBRARY_MESSAGES = "test-modules/library/i18n";

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("smc_module_test");
        jdbc = JdbcClient.create(ds);
        var mapper = new ObjectMapper();
        var repo = new ModuleRegistryRepository(jdbc, mapper);
        var auditRepo = new AuditLogRepository(jdbc, mapper);
        var auditService = new AuditLogService(auditRepo, null, new AuditDataRedactor());
        catalog = new ModuleCatalog(
                PlatformVersion.current(), ModuleManifests.read(ModuleRegistryIntegrationTest.class.getClassLoader()));
        moduleService = new ModuleRegistryService(repo, auditService, catalog, new MdI18nCatalog(mapper, catalog));
        newModule = new ModuleManifest(
                "library",
                PlatformVersion.parse("1.2.0"),
                PlatformVersion.parse("1.0.0"),
                List.of(new ModuleManifest.Dependency("iam", PlatformVersion.parse("1.0.0"))),
                List.of(),
                "com.acme.library.LibraryModule",
                null,
                LIBRARY_MESSAGES,
                "test");
        var withLibrary = new ModuleCatalog(
                PlatformVersion.current(),
                ModuleManifests.inDependencyOrder(Stream.concat(catalog.manifests().stream(), Stream.of(newModule))
                        .toList()));
        libraryTexts = new MdI18nCatalog(mapper, withLibrary);
        withNewModule = new ModuleRegistryService(repo, auditService, withLibrary, libraryTexts);
    }

    @Test
    @DisplayName("ADR-0033, 6.4: the registry shows the version, minPlatform and dependencies of the manifest")
    void builtInModulesShowTheirManifests() {
        var tasks = moduleService.getModule("tasks").orElseThrow();
        var manifest = catalog.find("tasks").orElseThrow();
        assertThat(tasks.version()).isEqualTo(manifest.version().toString()).isNotEqualTo("${project.version}");
        assertThat(tasks.minPlatform()).isEqualTo(PlatformVersion.current().toString());
        assertThat(tasks.dependencies())
                .extracting(ModuleRegistryService.ModuleDependencyView::code)
                .contains("iam");
        assertThat(moduleService.getAllModules())
                .as("every module the migrations register has a manifest")
                .filteredOn(module -> MIGRATED.contains(module.code()))
                .hasSize(MIGRATED.size())
                .allSatisfy(module -> {
                    assertThat(module.version()).as(module.code()).isNotNull();
                    assertThat(module.titleKey()).as(module.code()).isEqualTo(module.code() + ".module.name");
                    assertThat(module.descriptionKey())
                            .as(module.code())
                            .isEqualTo(module.code() + ".module.description");
                });
    }

    @Test
    @DisplayName("ADR-0033, 6.4: a manifest without a row gets one, switched on, once")
    void aNewModuleIsRegisteredFromItsManifest() {
        assertThat(withNewModule.registerManifests()).containsExactly("library");
        assertThat(withNewModule.registerManifests())
                .as("the second start registers nothing")
                .isEmpty();
        var library = withNewModule.getModule("library").orElseThrow();
        assertThat(library.status()).isEqualTo("ACTIVE");
        assertThat(library.isSystem()).isFalse();
        assertThat(library.name())
                .as("the row takes the Russian name of the module's catalog key")
                .isEqualTo(libraryTexts.bundled("ru").get("library.module.name"))
                .isNotBlank();
        assertThat(library.description()).isEqualTo(libraryTexts.bundled("ru").get("library.module.description"));
        assertThat(library.titleKey()).isEqualTo("library.module.name");
        assertThat(library.descriptionKey()).isEqualTo("library.module.description");
        assertThat(library.version()).isEqualTo("1.2.0");
        assertThat(library.minPlatform()).isEqualTo("1.0.0");
        assertThat(library.dependencies())
                .containsExactly(new ModuleRegistryService.ModuleDependencyView("iam", "1.0.0"));
        assertThat(withNewModule.toggleModuleStatus("library", false).status()).isEqualTo("DISABLED");
        assertThat(withNewModule.registerManifests()).isEmpty();
        assertThat(withNewModule.getModule("library").orElseThrow().status())
                .as("the administrator's switch stays")
                .isEqualTo("DISABLED");
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
        var registered = moduleService.putModule(
                "inventory",
                "Управление складом",
                "Учет товаров и остатков",
                "package",
                "/inventory",
                150,
                Map.of("currency", "USD"),
                null);

        assertThat(registered.code()).isEqualTo("inventory");
        assertThat(registered.status()).isEqualTo("ACTIVE");
        assertThat(registered.version())
                .as("a module without a manifest has no version")
                .isNull();
        assertThat(registered.name()).isEqualTo("Управление складом");
        assertThat(registered.titleKey())
                .as("a module without a manifest is shown by the registry's own name")
                .isNull();
        assertThat(registered.descriptionKey()).isNull();

        var found = moduleService.getModule("inventory");
        assertThat(found).isPresent();
        assertThat(found.get().attributes()).containsEntry("currency", "USD");
    }

    @Test
    @DisplayName("3.6: a new code of the API smoke registers without a revision; with one it is a replace, 404")
    void newSmokeCodeRegistersWithoutRevision() {
        String code = "smoke_module_123";
        Map<String, Object> noAttributes = Map.of();
        assertThatThrownBy(() -> moduleService.putModule(
                        code, "Smoke Test Module", null, "extension", "/custom/" + code, 900, noAttributes, 1L))
                .as("a revision left over from another record makes it a replace of a module that does not exist")
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        var registered = moduleService.putModule(
                code,
                "Smoke Test Module",
                "Registered by the nightly API smoke",
                "extension",
                "/custom/" + code,
                900,
                noAttributes,
                null);

        assertThat(registered.code()).isEqualTo(code);
        assertThat(registered.revision()).isEqualTo(1L);
        assertThat(registered.route()).isEqualTo("/custom/" + code);
        assertThat(moduleService.getModule(code))
                .hasValueSatisfying(found -> assertThat(found.attributes()).isEmpty());
    }

    @Test
    @DisplayName("3.6: PUT registers a new module; a replace names its revision: 428 without, 409 from an older one")
    void putModuleReplacesFromItsRevision() {
        var created = moduleService.putModule("warehouse", "Склад", null, "package", "/warehouse", 160, Map.of(), null);
        assertThat(created.revision()).isEqualTo(1L);
        assertThat(created.status()).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> moduleService.putModule(
                        "warehouse", "Без ревизии", null, "package", "/warehouse", 160, Map.of(), null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_REQUIRED));

        var replaced =
                moduleService.putModule("warehouse", "Склад 2", null, "package", "/warehouse", 160, Map.of(), 1L);
        assertThat(replaced.revision()).isEqualTo(2L);
        assertThat(replaced.name()).isEqualTo("Склад 2");

        assertThatThrownBy(() -> moduleService.putModule(
                        "warehouse", "Устаревшая", null, "package", "/warehouse", 160, Map.of(), 1L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVISION_CONFLICT));
        assertThatThrownBy(() ->
                        moduleService.putModule("nowhere", "Нет такого", null, "box", "/nowhere", 1, Map.of(), 1L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        // A switch is a write of the row too: a replace made from the revision before it gets 409.
        moduleService.toggleModuleStatus("warehouse", false);
        assertThat(moduleService.getModule("warehouse").orElseThrow().revision())
                .isEqualTo(3L);
        assertThatThrownBy(() -> moduleService.putModule(
                        "warehouse", "Поверх", null, "package", "/warehouse", 160, Map.of(), 2L))
                .isInstanceOf(ApiException.class);
        assertThat(moduleService.getModule("warehouse").orElseThrow().status())
                .as("a replace keeps the status")
                .isEqualTo("DISABLED");
    }
}
