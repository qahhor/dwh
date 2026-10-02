package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.pref.PermissionAreas;
import com.smartup24.cms.instance.support.V147FormCodes;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Plan 10/10, item 4.4 (ADR-0028): every permission code follows one rule. A form code is {@code <area>} or
 * {@code <area>.<entity-or-screen>}; the area names the owning module; a controller guards its endpoints with forms
 * of its own module, or with a form the owner publishes to that module.
 */
class PermissionCodesTest {

    private static final Path SOURCES = Path.of("src/main/java/com/smartup24/cms/instance");
    /** A form written as a literal where code checks a right: a field's right, a check, a refusal. */
    private static final Pattern LITERAL_CHECK =
            Pattern.compile("\\b(?:requires|hasPermission|permissionDenied)\\(\\s*\"([^\"]+)\"\\s*,");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ModuleBoundariesTest.ROOT);
    }

    /**
     * The violation of a {@code @RequiresPermission(form, ...)} on a controller of {@code module} (empty for the
     * infrastructure packages common and config, which belong to no module), or empty when the code obeys the rule.
     */
    static Optional<String> violation(Optional<String> module, String form) {
        if (!PermissionAreas.isWellFormed(form)) {
            return Optional.of(form + ": not <area> or <area>.<entity-or-screen> in lower-case segments");
        }
        Optional<String> owner = PermissionAreas.ownerOf(form);
        if (owner.isEmpty()) {
            return Optional.of(form + ": the area " + PermissionAreas.areaOf(form) + " names no module");
        }
        if (module.isPresent() && !PermissionAreas.usableBy(form, module.get())) {
            return Optional.of(form + ": owned by " + owner.get() + ", not published to " + module.get());
        }
        return Optional.empty();
    }

    @Test
    @DisplayName("4.4: every @RequiresPermission pair names a form of the controller's module or one published to it")
    void everyRequiresPermissionObeysTheRule() {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (JavaClass type : classes) {
            for (JavaMethod method : type.getMethods()) {
                if (!method.isAnnotatedWith(RequiresPermission.class)) {
                    continue;
                }
                RequiresPermission permission = method.getAnnotationOfType(RequiresPermission.class);
                checked++;
                Optional<String> module = ModuleBoundariesTest.moduleOf(type);
                violation(module, permission.form())
                        .ifPresent(problem ->
                                violations.add(type.getSimpleName() + "." + method.getName() + ": " + problem));
                if (!permission.action().matches("[a-z][a-z0-9_]*")) {
                    violations.add(type.getSimpleName() + "." + method.getName() + ": action " + permission.action());
                }
            }
        }
        assertThat(checked).as("annotated handlers found").isGreaterThan(100);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("4.4: a form written as a literal in a programmatic check obeys the rule as well")
    void literalFormsInProgrammaticChecksObeyTheRule() throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher literal = LITERAL_CHECK.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (literal.find()) {
                    checked++;
                    String form = literal.group(1);
                    violation(Optional.empty(), form)
                            .ifPresent(problem -> violations.add(file.getFileName() + ": " + problem));
                }
            }
        }
        assertThat(checked).as("literal checks found").isPositive();
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest(name = "{0} guarded with {1}")
    @CsvSource({
        // A screen-group prefix instead of the module: the area names nothing.
        "md, iam.users, names no module",
        "md, platform.settings, names no module",
        // Another module's form that its owner does not publish to this module.
        "upl, md.users, not published to upl",
        "search, md.users, not published to search",
        "report, md.roles, not published to report",
        // Not the shape of a form code.
        "md, md.users.list, not <area>",
        "md, Md.Users, not <area>"
    })
    @DisplayName("4.4: the rule rejects a code whose area does not lead to the controller's module")
    void ruleRejectsForeignAndUnknownAreas(String module, String form, String expected) {
        assertThat(violation(Optional.of(module), form))
                .hasValueSatisfying(problem -> assertThat(problem).contains(expected));
    }

    @ParameterizedTest(name = "{0} guarded with {1}")
    @CsvSource({
        "md, md.users",
        "ms.task, tasks.items",
        "ms.note, notes",
        "search, search",
        "webhook, webhook.subscriptions",
        // Published forms (ADR-0028, section 2).
        "kauth, md.profile",
        "kauth, md.users",
        "search, md.settings",
        "report, tasks.items"
    })
    @DisplayName("4.4: a module's own form and a form published to it pass")
    void ruleAcceptsOwnAndPublishedForms(String module, String form) {
        assertThat(violation(Optional.of(module), form)).isEmpty();
    }

    @Test
    @DisplayName("4.4: every area and every published form leads to a module the boundaries test knows")
    void areasAndPublishedFormsNameKnownModules() {
        Set<String> problems = new TreeSet<>();
        PermissionAreas.PUBLISHED.forEach((form, consumers) -> {
            Optional<String> owner = PermissionAreas.ownerOf(form);
            if (owner.isEmpty() || !ModuleBoundariesTest.MODULES.contains(owner.get())) {
                problems.add(form + ": no owning module");
            }
            consumers.forEach(consumer -> {
                if (!ModuleBoundariesTest.MODULES.contains(consumer) || owner.equals(Optional.of(consumer))) {
                    problems.add(form + ": consumer " + consumer);
                }
            });
        });
        for (String area : List.of(
                "analytics",
                "audit",
                "example",
                "jobs",
                "kauth",
                "md",
                "mf",
                "report",
                "search",
                "units",
                "upl",
                "warehouse",
                "tasks",
                "notes",
                "notify",
                "webhook")) {
            Optional<String> owner = PermissionAreas.ownerOf(area);
            if (owner.isEmpty() || !ModuleBoundariesTest.MODULES.contains(owner.get())) {
                problems.add(area + ": owner " + owner);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("4.4: an old code of V147 never obeys the rule, its successor always does")
    void oldCodesMapOntoCodesThatObeyTheRule() {
        Set<String> problems = new TreeSet<>();
        V147FormCodes.successors().forEach((old, current) -> {
            if (PermissionAreas.ownerOf(old).isPresent()) {
                problems.add(old + ": still parses as a current code");
            }
            if (PermissionAreas.ownerOf(current).isEmpty()) {
                problems.add(current + ": breaks the rule");
            }
        });
        assertThat(problems).isEmpty();
        assertThat(V147FormCodes.successors()).isNotEmpty();
    }
}
