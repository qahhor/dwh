package com.smartup24.cms.instance.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan 10/10, item 1.3: module boundaries as the documents draw them, checked on every build.
 *
 * <p>The violations that exist today are frozen ({@link FreezingArchRule}, store in
 * {@code src/test/resources/archunit_store}): a new one fails the build, a fixed one leaves the store, so the number
 * only goes down. Phases 2–4 remove them; the store's diff shows the progress in review.
 */
class ModuleBoundariesTest {

    static final String ROOT = "com.smartup24.cms.instance";
    /** Business modules; {@code ms} holds three of them. {@code common} and {@code config} are infrastructure. */
    static final List<String> MODULES = List.of(
            "analytics",
            "audit",
            "fnd",
            "kauth",
            "kwh",
            "md",
            "mf",
            "ms.note",
            "ms.notify",
            "ms.task",
            "report",
            "search",
            "upl");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    /** The business module of a class, or empty for infrastructure (common, config, the application class). */
    static Optional<String> moduleOf(JavaClass javaClass) {
        String name = javaClass.getPackageName();
        return MODULES.stream()
                .filter(module -> name.equals(ROOT + "." + module) || name.startsWith(ROOT + "." + module + "."))
                .findFirst();
    }

    @Test
    @DisplayName("1.3: common depends on no business module")
    void commonDependsOnNoBusinessModule() {
        String[] business =
                MODULES.stream().map(module -> ROOT + "." + module + "..").toArray(String[]::new);
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(ROOT + ".common..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(business)
                .as("common depends on no business module");
        FreezingArchRule.freeze(rule).check(classes);
    }

    @Test
    @DisplayName("3.2: a controller sees no repository package, nested records included")
    void controllersSeeNoRepositoryPackage() {
        // Strict since item 3.2: responses and requests are DTOs of the module's api package, built by services.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..repository..")
                .as("controllers see no repository package");
        rule.check(classes);
    }

    @Test
    @DisplayName("3.2: a REST controller, wherever it lives, sees no repository and no table row")
    void restControllersSeeNoRepositoryOrTableRow() {
        // Strict, not frozen: a handler answers the module's api DTOs, which services build from the rows.
        ArchRule rule = classes()
                .that()
                .areAnnotatedWith(RestController.class)
                .should(seeNoRepositoryOrTableRow())
                .as("REST controllers see no repository, no type nested in one and no table row");
        rule.check(classes);
    }

    /**
     * The class's own dependencies, and the parameter and return types of the methods it calls: a row held in a local
     * variable ({@code PackageRow row = service.get(id)}) is visible in bytecode only as the called method's type.
     */
    private static ArchCondition<JavaClass> seeNoRepositoryOrTableRow() {
        return new ArchCondition<>("see no repository, no type nested in one and no table row") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                controller.getDirectDependenciesFromSelf().stream()
                        .filter(dependency -> REPOSITORY_OR_TABLE_ROW.test(dependency.getTargetClass()))
                        .forEach(dependency ->
                                events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription())));
                controller.getCodeUnitCallsFromSelf().forEach(call -> {
                    var target = call.getTarget();
                    Stream.concat(Stream.of(target.getRawReturnType()), target.getRawParameterTypes().stream())
                            .filter(REPOSITORY_OR_TABLE_ROW)
                            .forEach(type -> events.add(SimpleConditionEvent.violated(
                                    call, call.getDescription() + " passes " + type.getName())));
                });
            }
        };
    }

    /**
     * A class of the application named {@code *Repository}, a type nested in one (its row records), or a table row
     * ({@code *Row}, the records of a module's model that mirror its tables, like {@code UplPackageModel.PackageRow}).
     */
    static final DescribedPredicate<JavaClass> REPOSITORY_OR_TABLE_ROW =
            DescribedPredicate.describe("a repository, a type nested in one, or a table row", javaClass -> {
                if (!javaClass.getPackageName().startsWith(ROOT)) {
                    return false;
                }
                JavaClass outermost = javaClass;
                while (outermost.getEnclosingClass().isPresent()) {
                    outermost = outermost.getEnclosingClass().get();
                }
                return outermost.getSimpleName().endsWith("Repository")
                        || javaClass.getSimpleName().endsWith("Row");
            });

    @Test
    @DisplayName("1.3: modules meet only through each other's service or api package")
    void modulesMeetThroughServiceOrApi() {
        ArchRule rule = classes()
                .should(onlyReachOtherModulesThroughServiceOrApi())
                .as("modules meet only through each other's service or api package");
        FreezingArchRule.freeze(rule).check(classes);
    }

    private static ArchCondition<JavaClass> onlyReachOtherModulesThroughServiceOrApi() {
        return new ArchCondition<>("only reach other modules through their service or api package") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                Optional<String> from = moduleOf(source);
                if (from.isEmpty()) {
                    return;
                }
                source.getDirectDependenciesFromSelf().forEach(dependency -> {
                    JavaClass target = dependency.getTargetClass();
                    Optional<String> to = moduleOf(target);
                    if (to.isEmpty() || to.equals(from)) {
                        return;
                    }
                    String prefix = ROOT + "." + to.get() + ".";
                    String pkg = target.getPackageName() + ".";
                    if (!pkg.startsWith(prefix + "service.") && !pkg.startsWith(prefix + "api.")) {
                        events.add(SimpleConditionEvent.violated(
                                dependency, from.get() + " -> " + to.get() + ": " + dependency.getDescription()));
                    }
                });
            }
        };
    }

    @Test
    @DisplayName("1.3: the frozen rules still catch what they forbid")
    void frozenRulesCatchViolations() {
        var probe = new ClassFileImporter().importClasses(ProbeController.class);
        ArchRule rule = noClasses()
                .that()
                .haveSimpleNameEndingWith("ProbeController")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..repository..");
        assertThat(rule.evaluate(probe).hasViolation()).isTrue();
    }

    /** A controller that reads a repository record: the kind of dependency the second rule forbids. */
    static final class ProbeController {
        KauthChannelRepository.ChannelRecord channel;
    }

    private static final Path STORE = Path.of("src/test/resources/archunit_store");
    private static final Path REPORT = Path.of("target/architecture/frozen-violations.md");

    @Test
    @DisplayName("1.3: the number of frozen violations is reported for CI")
    void frozenViolationsAreReported() throws IOException {
        Properties rules = new Properties();
        try (var in = Files.newBufferedReader(STORE.resolve("stored.rules"), StandardCharsets.UTF_8)) {
            rules.load(in);
        }
        StringBuilder report = new StringBuilder(
                "### Frozen architecture violations (plan 10/10, 1.3)\n\n" + "| Rule | Frozen |\n|---|---|\n");
        long total = 0;
        for (String rule : new TreeSet<>(rules.stringPropertyNames())) {
            long count = countLines(STORE.resolve(rules.getProperty(rule)));
            total += count;
            report.append("| ").append(rule).append(" | ").append(count).append(" |\n");
        }
        long foreignSql = countLines(FOREIGN_SQL_BASELINE);
        total += foreignSql;
        report.append("| repositories touch only their module's tables | ")
                .append(foreignSql)
                .append(" |\n")
                .append("| **total** | **")
                .append(total)
                .append("** |\n");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString(), StandardCharsets.UTF_8);
        assertThat(rules.stringPropertyNames())
                .as("every frozen rule has its store (controllers see no repository is strict since 3.2)")
                .hasSize(2);
    }

    /** Violations in a store file; comments and blank lines do not count. */
    private static long countLines(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .count();
        }
    }

    // ------------------------------------------------------------------
    // Foreign SQL: a module's repositories touch only its own tables
    // ------------------------------------------------------------------

    private static final Path SOURCES = Path.of("src/main/java/com/smartup24/cms/instance");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path FOREIGN_SQL_BASELINE = Path.of("src/test/resources/archunit_store/foreign-sql.txt");
    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)");
    private static final Pattern TABLE_USE = Pattern.compile("(?i)\\b(?:from|join|into|update)\\s+([a-z_][a-z0-9_]*)");

    /** The module that owns a table, by its prefix. */
    static Optional<String> ownerOf(String table) {
        if (table.startsWith("audit_log") || table.equals("security_events")) return Optional.of("audit");
        if (table.startsWith("ms_task")) return Optional.of("ms.task");
        if (table.startsWith("ms_notification") || table.startsWith("ms_announcement")) return Optional.of("ms.notify");
        if (table.startsWith("ms_note")) return Optional.of("ms.note");
        if (table.equals("idempotency_keys")) return Optional.of("config");
        for (String module : List.of("fnd", "kauth", "kwh", "md", "mf", "report", "search", "upl")) {
            if (table.startsWith(module + "_")) return Optional.of(module);
        }
        return Optional.empty();
    }

    @Test
    @DisplayName("1.3: a module's repositories touch only its own tables (frozen: only goes down)")
    void repositoriesTouchOnlyTheirModulesTables() throws IOException {
        Set<String> tables = knownTables();
        List<String> found;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            found = files.filter(file -> file.toString().replace('\\', '/').contains("/repository/")
                            && file.toString().endsWith(".java"))
                    .flatMap(file -> foreignTables(file, tables).stream())
                    .sorted()
                    .distinct()
                    .toList();
        }
        if (Boolean.getBoolean("archunit.freeze.store.default.allowStoreCreation")) {
            // The same switch that lets ArchUnit create its stores writes this baseline: once, when a rule is added.
            Files.createDirectories(FOREIGN_SQL_BASELINE.getParent());
            Files.write(
                    FOREIGN_SQL_BASELINE,
                    Stream.concat(
                                    Stream.of(
                                            "# Frozen foreign table access from repositories (plan 10/10, item 1.3): lines only go away."),
                                    found.stream())
                            .toList(),
                    StandardCharsets.UTF_8);
        }
        List<String> baseline = Files.readAllLines(FOREIGN_SQL_BASELINE, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        assertThat(found.stream().filter(line -> !baseline.contains(line)).toList())
                .as("new foreign table access from a repository: go through the owning module's service")
                .isEmpty();
        assertThat(baseline.stream().filter(line -> !found.contains(line)).toList())
                .as("fixed foreign table access: remove these lines from %s", FOREIGN_SQL_BASELINE)
                .isEmpty();
    }

    private static List<String> foreignTables(Path file, Set<String> tables) {
        String relative = SOURCES.relativize(file).toString().replace('\\', '/');
        String module = MODULES.stream()
                .filter(candidate -> relative.startsWith(candidate.replace('.', '/') + "/"))
                .findFirst()
                .orElse(null);
        if (module == null) {
            return List.of();
        }
        String className = relative.substring(relative.lastIndexOf('/') + 1).replace(".java", "");
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = TABLE_USE.matcher(source);
            TreeSet<String> result = new TreeSet<>();
            while (matcher.find()) {
                String table = matcher.group(1).toLowerCase();
                if (tables.contains(table)) {
                    ownerOf(table)
                            .filter(owner -> !owner.equals(module))
                            .ifPresent(owner -> result.add(module + " " + className + " -> " + table));
                }
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> knownTables() throws IOException {
        TreeSet<String> tables = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.toList()) {
                Matcher matcher = CREATE_TABLE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    tables.add(matcher.group(1).toLowerCase());
                }
            }
        }
        return tables;
    }
}
