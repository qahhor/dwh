package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 4.3: every module says what it is for. Each business module of {@link ModuleBoundariesTest#MODULES}
 * and the infrastructure packages {@code common} and {@code config} have a {@code package-info.java} with a Javadoc
 * paragraph, and exactly one row in the module map {@code docs/architecture/module-map.md}; the map has no row for a
 * module that does not exist, and no package under the root escapes the list.
 */
class ModuleMapTest {

    private static final Path SOURCES = Path.of("src/main/java/com/smartup24/cms/instance");
    private static final Path MODULE_MAP = Path.of("../../docs/architecture/module-map.md");
    private static final Path LIBS = Path.of("../../libs");
    private static final String PUBLIC_API_SECTION = "## \u041f\u0443\u0431\u043b\u0438\u0447\u043d\u044b\u0439 API";
    /** Infrastructure packages: described like modules, but not business modules. */
    private static final List<String> INFRASTRUCTURE = List.of("common", "config");
    /** Packages under the root that are neither: {@code ms} groups three modules under one Biruni prefix. */
    private static final Set<String> NOT_MODULES = Set.of("ms");

    /** A row of the map: {@code | `code` | `com.smartup24.cms.instance.<package>` | ...}. */
    private static final Pattern ROW = Pattern.compile("^\\|\\s*`([a-z][a-z0-9.]*)`\\s*\\|\\s*`"
            + Pattern.quote(ModuleBoundariesTest.ROOT) + "\\.([a-z0-9.]+)`\\s*\\|");

    private static final Pattern JAVADOC = Pattern.compile("(?s)/\\*\\*(.*?)\\*/");

    static List<String> describedModules() {
        List<String> modules = new ArrayList<>(ModuleBoundariesTest.MODULES);
        modules.addAll(INFRASTRUCTURE);
        return modules;
    }

    @Test
    @DisplayName("4.3: every module has a package-info.java with a Javadoc of its purpose")
    void everyModuleHasPackageInfo() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String module : describedModules()) {
            Path file = SOURCES.resolve(module.replace('.', '/')).resolve("package-info.java");
            if (!Files.isRegularFile(file)) {
                problems.add(module + ": no package-info.java");
                continue;
            }
            problemOf(module, Files.readString(file, StandardCharsets.UTF_8)).ifPresent(problems::add);
        }
        assertThat(problems)
                .as("a module root package says in package-info.java what the module is for")
                .isEmpty();
    }

    @Test
    @DisplayName("4.3: every module has exactly one row in the module map, and no row names a missing module")
    void everyModuleHasOneRowInTheMap() throws IOException {
        String map = Files.readString(MODULE_MAP, StandardCharsets.UTF_8);
        assertThat(mapProblems(map, describedModules()))
                .as("docs/architecture/module-map.md has one row per module of ModuleBoundariesTest.MODULES,"
                        + " common and config")
                .isEmpty();
    }

    @Test
    @DisplayName("4.3: every package under the root is a module, infrastructure or a known exception")
    void noPackageEscapesTheModuleList() throws IOException {
        Set<String> known = new TreeSet<>(NOT_MODULES);
        describedModules().forEach(module -> known.add(module.split("\\.")[0]));
        List<String> unknown;
        try (Stream<Path> children = Files.list(SOURCES)) {
            unknown = children.filter(Files::isDirectory)
                    .map(dir -> dir.getFileName().toString())
                    .filter(name -> !known.contains(name))
                    .sorted()
                    .toList();
        }
        assertThat(unknown)
                .as(
                        "a new package under %s is added to ModuleBoundariesTest.MODULES and to the module map",
                        ModuleBoundariesTest.ROOT)
                .isEmpty();
        try (Stream<Path> children = Files.list(SOURCES.resolve("ms"))) {
            assertThat(children.filter(Files::isDirectory)
                            .map(dir -> "ms." + dir.getFileName())
                            .filter(module -> !ModuleBoundariesTest.MODULES.contains(module))
                            .toList())
                    .as("each package under ms is a module of its own")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("4.3: the checks catch a missing, a doubled and a foreign row and an empty Javadoc")
    void checksCatchViolations() {
        String map = """
                | Code | Package | Purpose |
                |---|---|---|
                | `md` | `com.smartup24.cms.instance.md` | master data |
                | `md` | `com.smartup24.cms.instance.md` | master data again |
                | `ghost` | `com.smartup24.cms.instance.ghost` | no such module |
                | `mf` | `com.smartup24.cms.instance.ms.note` | wrong package |
                """;
        assertThat(mapProblems(map, List.of("md", "mf", "upl")))
                .containsExactlyInAnyOrder(
                        "md: 2 rows",
                        "ghost: a row for a module that does not exist",
                        "mf: the row names package ms.note",
                        "upl: no row");
        assertThat(problemOf("upl", "package com.smartup24.cms.instance.upl;\n"))
                .hasValue("upl: no Javadoc");
        assertThat(problemOf("upl", "/**\n *\n */\npackage com.smartup24.cms.instance.upl;\n"))
                .hasValue("upl: the Javadoc is empty");
        assertThat(problemOf("upl", "/** Uploads. */\npackage com.smartup24.cms.instance.upl;\n"))
                .isEmpty();
    }

    /**
     * ADR-0033, 4.1: the public API section of the map names every artifact of the public API — each library whose
     * build runs the japicmp compatibility gate ({@code libs/platform-api}, {@code libs/provider-spi}).
     */
    @Test
    @DisplayName("ADR-0033: the public API section names every artifact of the public API")
    void publicApiSectionNamesEveryApiArtifact() throws IOException {
        String map = Files.readString(MODULE_MAP, StandardCharsets.UTF_8);
        int start = map.indexOf(PUBLIC_API_SECTION);
        assertThat(start)
                .as("the section %s of the module map", PUBLIC_API_SECTION)
                .isNotNegative();
        int end = map.indexOf("\n## ", start + PUBLIC_API_SECTION.length());
        String section = map.substring(start, end < 0 ? map.length() : end);
        List<String> artifacts = new ArrayList<>();
        try (Stream<Path> libs = Files.list(LIBS)) {
            for (Path lib : libs.sorted().toList()) {
                Path pom = lib.resolve("pom.xml");
                if (Files.isRegularFile(pom)
                        && Files.readString(pom, StandardCharsets.UTF_8).contains("japicmp-maven-plugin")) {
                    artifacts.add("libs/" + lib.getFileName());
                }
            }
        }
        assertThat(artifacts).as("artifacts with the compatibility gate").contains("libs/platform-api");
        assertThat(artifacts)
                .as("named in the section %s", PUBLIC_API_SECTION)
                .allMatch(artifact -> section.contains("`" + artifact + "`"));
    }

    /** What is wrong with a package-info source, or empty when its Javadoc describes the module. */
    static Optional<String> problemOf(String module, String source) {
        int declaration = source.indexOf("package " + ModuleBoundariesTest.ROOT + "." + module + ";");
        if (declaration < 0) {
            return Optional.of(module + ": the file does not declare package " + module);
        }
        Matcher javadoc = JAVADOC.matcher(source.substring(0, declaration));
        if (!javadoc.find()) {
            return Optional.of(module + ": no Javadoc");
        }
        String text = javadoc.group(1).replaceAll("(?m)^\\s*\\*", " ").strip();
        return text.isEmpty() ? Optional.of(module + ": the Javadoc is empty") : Optional.empty();
    }

    /** Rows missing, doubled, misplaced or naming no module; empty when the map matches the modules. */
    static List<String> mapProblems(String map, List<String> modules) {
        Map<String, List<String>> rows = new TreeMap<>();
        for (String line : map.lines().toList()) {
            Matcher row = ROW.matcher(line);
            if (row.find()) {
                rows.computeIfAbsent(row.group(1), code -> new ArrayList<>()).add(row.group(2));
            }
        }
        List<String> problems = new ArrayList<>();
        for (String module : modules) {
            List<String> packages = rows.getOrDefault(module, List.of());
            if (packages.isEmpty()) {
                problems.add(module + ": no row");
            } else if (packages.size() > 1) {
                problems.add(module + ": " + packages.size() + " rows");
            } else if (!packages.get(0).equals(module)) {
                problems.add(module + ": the row names package " + packages.get(0));
            }
        }
        Set<String> expected = Set.copyOf(modules);
        problems.addAll(rows.keySet().stream()
                .filter(code -> !expected.contains(code))
                .map(code -> code + ": a row for a module that does not exist")
                .toList());
        return problems;
    }
}
