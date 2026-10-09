package com.smartup24.cms.instance.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
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
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
 * <p>Every rule is strict: phases 2–4 removed the violations that were once frozen, so a violation fails the build and
 * no store of accepted ones exists. Foreign SQL is strict as well: another module's data is read through its published
 * views (ADR-0026).
 */
class ModuleBoundariesTest {

    static final String ROOT = "com.smartup24.cms.instance";
    /** Business modules; {@code ms} holds three of them. {@code common} and {@code config} are infrastructure. */
    static final List<String> MODULES = List.of(
            "analytics",
            "audit",
            "example",
            "jobs",
            "kauth",
            "md",
            "mf",
            "ms.note",
            "ms.notify",
            "ms.task",
            "report",
            "search",
            "units",
            "upl",
            "warehouse",
            "webhook");
    /**
     * Modules of the platform the business modules stand on: they depend on no business module. A platform contract
     * in {@code common} ({@code AuditActorContext}, {@code SecurityEventLog}) inverts what they would need from one.
     */
    static final List<String> INFRASTRUCTURE = List.of("jobs", "units", "warehouse");

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
        rule.check(classes);
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
        rule.check(classes);
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

    /** Each business module is a slice of its own ({@code ms.task} and {@code ms.notify} apart); infrastructure none. */
    static final SliceAssignment BUSINESS_MODULES = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            return moduleOf(javaClass).map(SliceIdentifier::of).orElseGet(SliceIdentifier::ignore);
        }

        @Override
        public String getDescription() {
            return "business modules";
        }
    };

    @Test
    @DisplayName("1.3: business modules depend on each other without cycles")
    void businessModulesHaveNoCycles() {
        // A module that reacts to another listens to its events (api package); the other never calls it back.
        slices().assignedFrom(BUSINESS_MODULES)
                .should()
                .beFreeOfCycles()
                .as("business modules depend on each other without cycles")
                .check(classes);
    }

    @Test
    @DisplayName("1.3: the wiring (config) reaches modules only through their service or api package")
    void wiringReachesModulesThroughServiceOrApi() {
        // Strict: what the wiring needs from a module (a filter, a probe, a driver) the module publishes as a contract
        // in its api package; a module registers its own MVC and health parts where it can.
        ArchRule rule = classes()
                .that()
                .resideInAPackage(ROOT + ".config..")
                .should(reachModulesOnlyThroughServiceOrApi())
                .as("the wiring reaches modules only through their service or api package");
        rule.check(classes);
    }

    @Test
    @DisplayName("1.3: no module depends on the wiring (config)")
    void noModuleDependsOnTheWiring() {
        String[] modules =
                MODULES.stream().map(module -> ROOT + "." + module + "..").toArray(String[]::new);
        ArchRule rule = noClasses()
                .that()
                .resideInAnyPackage(modules)
                .or()
                .resideInAPackage(ROOT + ".common..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".config..")
                .as("no module depends on the wiring");
        rule.check(classes);
    }

    @Test
    @DisplayName("1.3: infrastructure modules (jobs, units, warehouse) depend on no business module")
    void infrastructureDependsOnNoBusinessModule() {
        String[] infrastructure = INFRASTRUCTURE.stream()
                .map(module -> ROOT + "." + module + "..")
                .toArray(String[]::new);
        String[] business = MODULES.stream()
                .filter(module -> !INFRASTRUCTURE.contains(module))
                .map(module -> ROOT + "." + module + "..")
                .toArray(String[]::new);
        ArchRule rule = noClasses()
                .that()
                .resideInAnyPackage(infrastructure)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(business)
                .as("infrastructure modules depend on no business module; a platform contract in common inverts it");
        rule.check(classes);
    }

    private static ArchCondition<JavaClass> reachModulesOnlyThroughServiceOrApi() {
        return new ArchCondition<>("reach modules only through their service or api package") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                source.getDirectDependenciesFromSelf().forEach(dependency -> {
                    JavaClass target = dependency.getTargetClass();
                    Optional<String> to = moduleOf(target);
                    if (to.isEmpty()) {
                        return;
                    }
                    String prefix = ROOT + "." + to.get() + ".";
                    String pkg = target.getPackageName() + ".";
                    if (!pkg.startsWith(prefix + "service.") && !pkg.startsWith(prefix + "api.")) {
                        events.add(SimpleConditionEvent.violated(
                                dependency, "config -> " + to.get() + ": " + dependency.getDescription()));
                    }
                });
            }
        };
    }

    @Test
    @DisplayName("1.3: the rules still catch what they forbid")
    void rulesCatchViolations() {
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

    // ------------------------------------------------------------------
    // Foreign SQL: a module's repositories touch only its own tables (ADR-0026)
    // ------------------------------------------------------------------

    private static final Path SOURCES = Path.of("src/main/java/com/smartup24/cms/instance");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)");
    private static final Pattern CREATE_VIEW =
            Pattern.compile("(?i)create\\s+(?:or\\s+replace\\s+)?view\\s+([a-z_][a-z0-9_]*)");
    /** A relation after a SQL keyword, or a relation name alone in a string literal (built into SQL later). */
    private static final Pattern TABLE_USE = Pattern.compile(
            "(?i)\\b(delete\\s+from|from|join|into|update)\\s+([a-z_][a-z0-9_]*)|\"([a-z_][a-z0-9_]*)\"");
    /** Two string literals joined by {@code +}: {@code "from md_" + "users"} reads as {@code "from md_users"}. */
    private static final Pattern JOINED_LITERALS = Pattern.compile("\"\\s*\\+\\s*\"");
    /** A source that runs SQL wherever it lives: a JDBC client, template or statement. */
    private static final Pattern RUNS_SQL = Pattern.compile(
            "\\bJdbc(?:Client|Template|Operations)\\b|\\.(?:prepareStatement|prepareCall|createStatement)\\(");
    /** The wiring: no business module, yet its SQL is held to the same rule (ADR-0026). */
    private static final String CONFIG = "config";
    /** A published read view: {@code <owner prefix>_pub_<name>} (ADR-0026). */
    static final Pattern PUBLISHED_VIEW = Pattern.compile("^([a-z][a-z0-9_]*?)_pub_[a-z0-9_]+$");

    /**
     * The module that owns a table or a published view, by its prefix. {@code md_sso_providers} carries the md prefix
     * from V017 but only kauth reads it (SSO sign-in): the code that holds a table owns it (ADR-0026). The tables of the
     * former foundation keep their {@code fnd_} names (ADR-0020): {@code fnd_job_*} belong to jobs, {@code fnd_unit*}
     * to units, {@code fnd_load*} to warehouse; {@code fnd_versioned_tables} and {@code fnd_audit_tables} are registries
     * of the platform in common, which owns no module (ADR-0030). The {@code kwh_} tables keep their names as well and
     * belong to webhook (plan 10/10, item 4.3).
     */
    static Optional<String> ownerOf(String table) {
        if (table.startsWith("audit_log") || table.equals("security_events")) return Optional.of("audit");
        if (table.equals("md_sso_providers")) return Optional.of("kauth");
        if (table.startsWith("ms_task")) return Optional.of("ms.task");
        if (table.startsWith("ms_notification") || table.startsWith("ms_announcement")) return Optional.of("ms.notify");
        if (table.startsWith("ms_note")) return Optional.of("ms.note");
        if (table.equals("idempotency_keys")) return Optional.of("config");
        if (table.startsWith("fnd_job_")) return Optional.of("jobs");
        if (table.startsWith("fnd_unit")) return Optional.of("units");
        if (table.startsWith("fnd_load")) return Optional.of("warehouse");
        if (table.startsWith("kwh_")) return Optional.of("webhook");
        if (table.startsWith("ex_")) return Optional.of("example");
        for (String module : List.of("kauth", "md", "mf", "report", "search", "upl")) {
            if (table.startsWith(module + "_")) return Optional.of(module);
        }
        return Optional.empty();
    }

    /**
     * The check reads source text, so it sees what a regular expression can: a relation after a SQL keyword, a relation
     * name alone in a string literal (a constant built into SQL later) and literals joined by {@code +}. It does not
     * see a name assembled at run time from a prefix and a variable; such SQL is not written in this code base, and a
     * review keeps it out. The wiring ({@code config}) is checked as well: it owns only {@code idempotency_keys}, and the
     * first start writes md's tables through md's service.
     */
    @Test
    @DisplayName(
            "1.3: code that runs SQL reads other modules only through their published views and writes only its own")
    void repositoriesTouchOnlyTheirModulesTables() throws IOException {
        Set<String> tables = createdRelations(CREATE_TABLE);
        Set<String> views = createdRelations(CREATE_VIEW);
        List<String> found;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            found = files.filter(file -> file.toString().endsWith(".java"))
                    .flatMap(file -> foreignAccess(file, tables, views).stream())
                    .sorted()
                    .distinct()
                    .toList();
        }
        assertThat(found)
                .as("read another module's published view (<owner>_pub_*, ADR-0026) or call its service;"
                        + " a write always goes through the owning module's service")
                .isEmpty();
    }

    @Test
    @DisplayName("ADR-0026: every view is a published view with an owning module")
    void everyViewIsPublishedByAModule() throws IOException {
        List<String> wrong = createdRelations(CREATE_VIEW).stream()
                .filter(view -> !PUBLISHED_VIEW.matcher(view).matches()
                        || ownerOf(view).isEmpty()
                        || !ownerOf(view).equals(ownerOf(prefixOf(view) + "_")))
                .toList();
        assertThat(wrong).as("a view is named <owner prefix>_pub_<name>").isEmpty();
        assertThat(foreignAccessIn("ms/task/repository/Probe.java", "select 1 from md_users", Set.of("md_users")))
                .containsExactly("ms.task Probe -> md_users");
        assertThat(foreignAccessIn("ms/task/repository/Probe.java", "join md_pub_users u", Set.of()))
                .isEmpty();
        assertThat(foreignAccessIn("ms/task/repository/Probe.java", "update md_pub_users set", Set.of()))
                .containsExactly("ms.task Probe writes md_pub_users");
        assertThat(foreignAccessIn("md/repository/Probe.java", "delete from md_pub_users", Set.of()))
                .containsExactly("md Probe writes md_pub_users");
        assertThat(foreignAccessIn(
                        "ms/task/repository/Probe.java", "case \"USER\" -> \"md_users\";", Set.of("md_users")))
                .containsExactly("ms.task Probe -> md_users");
        assertThat(foreignAccessIn(
                        "ms/task/repository/Probe.java", "\"select 1 from md_\" + \"users\"", Set.of("md_users")))
                .containsExactly("ms.task Probe -> md_users");
        assertThat(foreignAccessIn(
                        "ms/task/service/Probe.java",
                        "JdbcClient jdbc; String sql = \"select 1 from md_users\";",
                        Set.of("md_users")))
                .containsExactly("ms.task Probe -> md_users");
        assertThat(foreignAccessIn(
                        "config/bootstrap/Probe.java",
                        "JdbcClient jdbc; String sql = \"insert into md_users\";",
                        Set.of("md_users")))
                .containsExactly("config Probe -> md_users");
        assertThat(foreignAccessIn(
                        "config/idempotency/Probe.java",
                        "JdbcClient jdbc; String sql = \"delete from idempotency_keys\";",
                        Set.of("idempotency_keys")))
                .isEmpty();
        assertThat(foreignAccessIn("ms/task/service/Probe.java", "String text = \"md_users\";", Set.of("md_users")))
                .as("a class that runs no SQL may name a table, in a message for one")
                .isEmpty();
    }

    private static String prefixOf(String view) {
        Matcher matcher = PUBLISHED_VIEW.matcher(view);
        return matcher.matches() ? matcher.group(1) : view;
    }

    private static List<String> foreignAccess(Path file, Set<String> tables, Set<String> views) {
        String relative = SOURCES.relativize(file).toString().replace('\\', '/');
        try {
            Set<String> relations = new TreeSet<>(tables);
            relations.addAll(views);
            return foreignAccessIn(relative, Files.readString(file, StandardCharsets.UTF_8), relations);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Violations in one source that runs SQL (a repository, or any class with a JDBC client, template or statement): a
     * table of another module, a write to any published view, a view of another module that is not published.
     * Published views need not be in {@code relations}: the name says it.
     */
    static List<String> foreignAccessIn(String relative, String source, Set<String> relations) {
        String module = Stream.concat(MODULES.stream(), Stream.of(CONFIG))
                .filter(candidate -> relative.startsWith(candidate.replace('.', '/') + "/"))
                .findFirst()
                .orElse(null);
        if (module == null
                || !(relative.contains("/repository/")
                        || RUNS_SQL.matcher(source).find())) {
            return List.of();
        }
        String className = relative.substring(relative.lastIndexOf('/') + 1).replace(".java", "");
        Matcher matcher = TABLE_USE.matcher(JOINED_LITERALS.matcher(source).replaceAll(""));
        TreeSet<String> result = new TreeSet<>();
        while (matcher.find()) {
            String keyword = matcher.group(1) == null ? "" : matcher.group(1).toLowerCase();
            String name = (matcher.group(2) != null ? matcher.group(2) : matcher.group(3)).toLowerCase();
            boolean published =
                    PUBLISHED_VIEW.matcher(name).matches() && ownerOf(name).isPresent();
            if (published && (keyword.startsWith("delete") || keyword.equals("into") || keyword.equals("update"))) {
                result.add(module + " " + className + " writes " + name);
            } else if (!published && relations.contains(name)) {
                ownerOf(name)
                        .filter(owner -> !owner.equals(module))
                        .ifPresent(owner -> result.add(module + " " + className + " -> " + name));
            }
        }
        return List.copyOf(result);
    }

    private static Set<String> createdRelations(Pattern statement) throws IOException {
        TreeSet<String> relations = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.toList()) {
                Matcher matcher = statement.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    relations.add(matcher.group(1).toLowerCase());
                }
            }
        }
        return relations;
    }
}
