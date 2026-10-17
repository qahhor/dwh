package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simple-form contract reserves every platform module code (ADR-0037, ADR-0038): the server refuses a package
 * that names one, and the CLI refuses to deliver a static entity into one or into the live namespace.
 *
 * <p>The platform modules are listed here, not derived from the module lists: {@code cms module new} adds an ordinary
 * business module to those lists, and such a module may receive delivered forms. The check runs both ways: each
 * listed code must still be a module, a manifest or a package of the platform, so the list cannot go stale unnoticed,
 * and each package of the server and each built-in manifest must be reserved or named as a business module below, so
 * a new platform area cannot be forgotten.
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

    /**
     * Business modules of this repository that sit among the server's packages or built-in manifests and may receive
     * delivered forms, each with the reason it is not a platform module. Empty today.
     */
    private static final Map<String, String> BUSINESS_MODULES = Map.of();

    /** Named permission areas of modules under the {@code ms} prefix (PermissionAreas). */
    private static final Set<String> NAMED_AREAS = Set.of("notes", "notify", "tasks");

    @Test
    @DisplayName("ADR-0038: every platform module code is reserved, and the live namespace is studio alone")
    void everyPlatformModuleIsReserved() throws IOException {
        JsonNode contract = contract();
        assertThat(names(contract.path("platformModules"))).containsExactlyInAnyOrderElementsOf(PLATFORM);
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

    @Test
    @DisplayName("ADR-0038: every server package and built-in manifest is reserved or a named business module")
    void everyPackageAndManifestIsReserved() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> packages = Files.list(PACKAGES)) {
            packages.filter(Files::isDirectory)
                    .forEach(path -> found.add(path.getFileName().toString()));
        }
        try (Stream<Path> manifests = Files.list(MANIFESTS)) {
            manifests
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .forEach(name -> found.add(name.substring(0, name.length() - ".json".length())));
        }
        Set<String> reserved = names(contract().path("platformModules"));
        assertThat(unreserved(found, reserved, BUSINESS_MODULES))
                .as(
                        "add the code to platformModules of %s and to PLATFORM, or to BUSINESS_MODULES with a reason",
                        CONTRACT)
                .isEmpty();
        for (String business : BUSINESS_MODULES.keySet()) {
            assertThat(found).as("stale business module %s", business).contains(business);
            assertThat(reserved).as("business module %s is reserved", business).doesNotContain(business);
        }
    }

    @Test
    @DisplayName("ADR-0038: the reverse check names a package that is neither reserved nor a business module")
    void theReverseCheckNamesAnUnreservedPackage() {
        assertThat(unreserved(Set.of("md", "billing", "crm"), Set.of("md"), Map.of("crm", "a business module")))
                .containsExactly("billing");
    }

    private static Set<String> unreserved(Set<String> found, Set<String> reserved, Map<String, String> business) {
        Set<String> result = new TreeSet<>(found);
        result.removeAll(reserved);
        result.removeAll(business.keySet());
        return result;
    }

    private static JsonNode contract() throws IOException {
        return JsonMapper.shared().readTree(Files.readString(CONTRACT));
    }

    private static Set<String> names(JsonNode values) {
        Set<String> result = new TreeSet<>();
        values.forEach(value -> result.add(value.asString()));
        return result;
    }
}
