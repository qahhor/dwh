package com.smartup24.cms.instance.config.env;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.validation.annotation.Validated;

/**
 * The configuration names of plan 10/10, item 4.1 (ADR-0027): the product under {@code smc}, the warehouse under
 * {@code warehouse}, no property named {@code dwh} left, and every bound settings class validated.
 */
class ConfigurationNamesTest {

    private static final String ROOT = "com.smartup24.cms";
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path RESOURCES = Path.of("src/main/resources");

    /** A property key in code: a placeholder, a conditional property name or a properties prefix. */
    private static final Pattern OLD_KEY_IN_CODE =
            Pattern.compile("\\$\\{(?:app\\.)?dwh\\.|(?:name|prefix) = \"(?:app\\.)?dwh[.\"]");

    @Test
    @DisplayName("4.1: каждый класс @ConfigurationProperties помечен @Validated и живёт под smc или warehouse")
    void propertiesClassesAreValidatedAndNamed() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        classes()
                .that()
                .areAnnotatedWith(ConfigurationProperties.class)
                .should()
                .beAnnotatedWith(Validated.class)
                .andShould(bindUnderACurrentPrefix())
                .check(imported);
    }

    @Test
    @DisplayName("4.1: в application*.yml нет свойств dwh.* и app.dwh.*")
    void configurationFilesHaveNoOldKeys() throws IOException {
        List<String> old = new ArrayList<>();
        try (Stream<Path> files = Files.list(RESOURCES)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("application.*\\.ya?ml"))
                    .toList()) {
                for (PropertySource<?> document :
                        new YamlPropertySourceLoader().load(file.toString(), new FileSystemResource(file))) {
                    for (String key : ((EnumerablePropertySource<?>) document).getPropertyNames()) {
                        if (LegacyConfigNames.property(key).isPresent()) {
                            old.add(file.getFileName() + ": " + key);
                        }
                    }
                }
            }
        }
        assertThat(old).isEmpty();
    }

    @Test
    @DisplayName("4.1: код читает свойства только по новым именам")
    void codeReadsNoOldKeys() throws IOException {
        List<String> old = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (OLD_KEY_IN_CODE.matcher(lines.get(i)).find()) {
                        old.add(SOURCES.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(old).isEmpty();
    }

    private static ArchCondition<JavaClass> bindUnderACurrentPrefix() {
        return new ArchCondition<>("bind under smc.* or warehouse") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                ConfigurationProperties annotation = javaClass.reflect().getAnnotation(ConfigurationProperties.class);
                String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
                if (!prefix.startsWith("smc.") && !prefix.equals("warehouse") && !prefix.startsWith("warehouse.")) {
                    events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + " binds " + prefix));
                }
            }
        };
    }
}
