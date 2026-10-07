package com.smartup24.cms.instance.common.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.platform.api.PlatformVersion;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The manifest of a module and the start check of ADR-0033, 6.2–6.3 (plan 10/10, item 6.4). */
class ModuleManifestsTest {

    private static final PlatformVersion PLATFORM = PlatformVersion.parse("1.3.0");

    private static ModuleManifest parse(String file, String json) {
        return ModuleManifests.parse(
                "test:" + file, file, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static ModuleManifest module(String code, String version, String minPlatform, String... needs) {
        List<ModuleManifest.Dependency> dependencies = java.util.Arrays.stream(needs)
                .map(need -> need.split("@"))
                .map(pair -> new ModuleManifest.Dependency(pair[0], PlatformVersion.parse(pair[1])))
                .toList();
        return new ModuleManifest(
                code,
                code,
                PlatformVersion.parse(version),
                PlatformVersion.parse(minPlatform),
                dependencies,
                List.of(),
                null,
                null,
                null,
                "test:" + code);
    }

    @Test
    void readsAManifest() {
        ModuleManifest manifest = parse("library.json", """
                {"code": "library", "name": "Library", "version": "1.2.0", "minPlatform": "1.0.0",
                 "dependencies": [{"code": "iam", "version": "1.0.0"}],
                 "configuration": "com.acme.library.LibraryModule", "migrations": "db/modules/library",
                 "messages": "META-INF/smartupcms/modules/library/i18n"}
                """);
        assertThat(manifest.code()).isEqualTo("library");
        assertThat(manifest.version()).isEqualTo(PlatformVersion.parse("1.2.0"));
        assertThat(manifest.dependencies())
                .containsExactly(new ModuleManifest.Dependency("iam", PlatformVersion.parse("1.0.0")));
        assertThat(manifest.configuration()).isEqualTo("com.acme.library.LibraryModule");
        assertThat(manifest.historyTable()).isEqualTo("flyway_module_library");
    }

    @Test
    void refusesAMalformedManifestNamingItsFile() {
        assertThatThrownBy(() -> parse("library.json", """
                        {"code": "library", "name": "L", "version": "1.2.0", "minPlatfrom": "1.0.0"}
                        """))
                .isInstanceOf(ModuleManifestException.class)
                .hasMessageContaining("test:library.json")
                .hasMessageContaining("unknown field minPlatfrom");
        assertThatThrownBy(() -> parse("other.json", """
                        {"code": "library", "name": "L", "version": "1.2.0", "minPlatform": "1.0.0"}
                        """)).hasMessageContaining("named library.json");
        assertThatThrownBy(() -> parse("library.json", """
                        {"code": "library", "name": "L", "version": "1.2", "minPlatform": "1.0.0"}
                        """)).hasMessageContaining("MAJOR.MINOR.PATCH");
        assertThatThrownBy(() -> parse("library.json", "[1]")).hasMessageContaining("JSON object");
        assertThatThrownBy(() -> parse("library.json", """
                        {"code": "library", "name": "L", "version": "1.0.0", "minPlatform": "1.0.0",
                         "dependencies": [{"code": "iam"}]}
                        """)).hasMessageContaining("no version");
        assertThatThrownBy(() -> parse("library.json", """
                        {"code": "library", "name": 7, "version": "1.0.0", "minPlatform": "1.0.0"}
                        """)).hasMessageContaining("name is a string");
        assertThatThrownBy(() -> module("library", "1.0.0", "1.0.0", "library@1.0.0"))
                .hasMessageContaining("depends on itself");
    }

    @Test
    void aModuleNeedingANewerPlatformIsRefusedWithItsNameAndVersions() {
        assertThat(ModuleManifests.problems(List.of(module("library", "1.2.0", "1.4.0")), PLATFORM))
                .containsExactly("module library 1.2.0 needs platform API >= 1.4.0 (major 1); this platform"
                        + " provides 1.3.0");
        assertThat(ModuleManifests.problems(List.of(module("library", "1.2.0", "2.0.0")), PLATFORM))
                .singleElement()
                .asString()
                .contains("major 2");
        assertThat(ModuleManifests.problems(List.of(module("library", "1.2.0", "1.0.0")), PLATFORM))
                .isEmpty();
    }

    @Test
    void aMissingOrOlderDependencyIsRefused() {
        List<ModuleManifest> modules = List.of(
                module("iam", "1.0.0-SNAPSHOT", "1.0.0"),
                module("library", "1.0.0", "1.0.0", "iam@1.0.0", "reports@2.0.0"),
                module("shelf", "1.0.0", "1.0.0", "iam@1.1.0"));
        assertThat(ModuleManifests.problems(modules, PLATFORM))
                .containsExactly(
                        "module library 1.0.0 needs module reports >= 2.0.0, which is not installed",
                        "module shelf 1.0.0 needs module iam >= 1.1.0 (major 1); installed is 1.0.0-SNAPSHOT");
    }

    @Test
    void aCodeDeclaredTwiceAndACycleAreRefused() {
        assertThat(ModuleManifests.problems(
                        List.of(module("iam", "1.0.0", "1.0.0"), module("iam", "1.0.0", "1.0.0")), PLATFORM))
                .singleElement()
                .asString()
                .contains("declared twice");
        assertThat(ModuleManifests.problems(
                        List.of(module("aa", "1.0.0", "1.0.0", "bb@1.0.0"), module("bb", "1.0.0", "1.0.0", "aa@1.0.0")),
                        PLATFORM))
                .containsExactly("modules depend on each other in a cycle: aa -> bb -> aa");
    }

    /** ADR-0032, 6.3, step 1: the permission areas a module holds beside its code decide the module of an entity. */
    @Test
    void readsThePermissionAreasAndRefusesOneHeldTwice() {
        ModuleManifest iam = parse("iam.json", """
                {"code": "iam", "name": "IAM", "version": "1.0.0", "minPlatform": "1.0.0", "areas": ["md"]}
                """);
        assertThat(iam.areas()).containsExactly("md");
        assertThat(iam.holds("md")).isTrue();
        assertThat(iam.holds("iam")).isTrue();
        assertThat(iam.holds("tasks")).isFalse();
        assertThatThrownBy(() -> parse("iam.json", """
                        {"code": "iam", "name": "IAM", "version": "1.0.0", "minPlatform": "1.0.0", "areas": ["Md"]}
                        """)).hasMessageContaining("bad permission area Md");

        ModuleManifest other = parse("crm.json", """
                {"code": "crm", "name": "CRM", "version": "1.0.0", "minPlatform": "1.0.0", "areas": ["md"]}
                """);
        assertThat(ModuleManifests.problems(List.of(iam, other), PLATFORM))
                .containsExactly("permission area md of module crm is held by module iam");
        assertThat(new ModuleCatalog(PLATFORM, List.of(iam)).holding("md")).contains(iam);
    }

    @Test
    void ordersEveryModuleAfterTheModulesItNeeds() {
        List<ModuleManifest> ordered = ModuleManifests.inDependencyOrder(List.of(
                module("aa", "1.0.0", "1.0.0", "zz@1.0.0"),
                module("zz", "1.0.0", "1.0.0", "mm@1.0.0"),
                module("mm", "1.0.0", "1.0.0"),
                module("bb", "1.0.0", "1.0.0")));
        assertThat(ordered).extracting(ModuleManifest::code).containsExactly("mm", "zz", "aa", "bb");
    }

    @Test
    void theBuiltInModulesPassTheCheckOfThisPlatform() {
        List<ModuleManifest> builtIn = ModuleManifests.checked(getClass().getClassLoader(), PlatformVersion.current());
        assertThat(builtIn)
                .extracting(ModuleManifest::code)
                .contains("iam", "tasks", "files", "audit", "search", "notes", "upl", "example");
        assertThat(builtIn.getFirst().code()).as("iam is needed by every other").isEqualTo("iam");
        assertThat(builtIn).allSatisfy(manifest -> {
            assertThat(manifest.minPlatform()).isEqualTo(PlatformVersion.current());
            assertThat(manifest.configuration())
                    .as("found by the server's own scan")
                    .isNull();
        });
    }
}
