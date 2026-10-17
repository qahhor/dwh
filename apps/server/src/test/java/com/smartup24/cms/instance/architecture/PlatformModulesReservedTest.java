package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simple-form contract reserves every platform module code (ADR-0037, ADR-0038): the server refuses a package
 * that names one, and the CLI refuses to deliver a static entity into one or into the live namespace.
 *
 * <p>The platform modules are listed here, not derived from the module lists: {@code cms module new} adds an ordinary
 * business module to those lists, and such a module may receive delivered forms. Each listed code must still be a
 * module, a manifest or a package of the platform, so the list cannot go stale unnoticed.
 */
class PlatformModulesReservedTest {
    private static final Path CONTRACT = Path.of("src/main/resources/studio/simple-form-reserved-identifiers.json");
    private static final Path MANIFESTS = Path.of("src/main/resources/META-INF/smartupcms/modules");
    private static final Path PACKAGES = Path.of("src/main/java/com/smartup24/cms/instance");

    /** The modules, manifests, named areas and infrastructure packages of the platform itself. */
    private static final Set<String> PLATFORM = Set.of(
            "analytics",
            "audit",
            "common",
            "config",
            "example",
            "files",
            "iam",
            "jobs",
            "kauth",
            "md",
            "mf",
            "ms",
            "notes",
            "notify",
            "report",
            "search",
            "studio",
            "tasks",
            "units",
            "upl",
            "warehouse",
            "webhook");

    /** Named permission areas of modules under the {@code ms} prefix (PermissionAreas). */
    private static final Set<String> NAMED_AREAS = Set.of("notes", "notify", "tasks");

    @Test
    @DisplayName("ADR-0038: every platform module code is reserved, and the live namespace is studio alone")
    void everyPlatformModuleIsReserved() throws IOException {
        JsonNode contract = JsonMapper.shared().readTree(Files.readString(CONTRACT));
        assertThat(names(contract.path("platformModules"))).containsAll(PLATFORM);
        assertThat(names(contract.path("liveModules"))).containsExactly("studio");
    }

    @Test
    @DisplayName("ADR-0038: each listed platform code is still a module, a manifest, a named area or a package")
    void everyListedCodeStillExists() {
        Set<String> modules = new TreeSet<>(NAMED_AREAS);
        ModuleBoundariesTest.MODULES.forEach(module -> modules.add(module.split("\\.")[0]));
        for (String code : PLATFORM) {
            assertThat(modules.contains(code)
                            || Files.exists(MANIFESTS.resolve(code + ".json"))
                            || Files.isDirectory(PACKAGES.resolve(code)))
                    .as(code)
                    .isTrue();
        }
    }

    private static Set<String> names(JsonNode values) {
        Set<String> result = new TreeSet<>();
        values.forEach(value -> result.add(value.asString()));
        return result;
    }
}
