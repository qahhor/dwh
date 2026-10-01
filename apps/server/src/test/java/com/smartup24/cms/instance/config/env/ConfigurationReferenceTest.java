package com.smartup24.cms.instance.config.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * The reference of configuration variables, docs/ops/configuration-reference.md, is generated from the configuration
 * the server binds (plan 10/10, item 4.1; ADR-0027) and must match it: CI fails on drift.
 *
 * <p>Sources: the placeholders of application.yml (variable, property, default), the {@code @ConfigurationProperties}
 * classes (their keys and {@code @DefaultValue}s) and the {@code @Value} / {@code @ConditionalOnProperty} keys of the
 * code. Only the part between the markers is generated; the text around it is written by hand. Regenerate with
 * {@code mvn test -pl apps/server -Dtest=ConfigurationReferenceTest -Dconfig.reference.update=true}.
 */
class ConfigurationReferenceTest {

    static final Path COMMITTED = Path.of("../../docs/ops/configuration-reference.md");
    static final String START = "<!-- generated:start ConfigurationReferenceTest -->";
    static final String END = "<!-- generated:end -->";

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*)(?::(.*))?}$", Pattern.DOTALL);
    private static final Pattern VALUE_KEY = Pattern.compile("\\$\\{((?:smc|warehouse)\\.[a-z0-9.\\-]+)(?::([^}]*))?}");
    private static final Pattern CONDITION_KEY = Pattern.compile(
            "@ConditionalOnProperty\\(name = \"((?:smc|warehouse)\\.[^\"]+)\"[^)]*matchIfMissing = true");
    private static final Pattern LIST_INDEX = Pattern.compile("\\[\\d+]$");

    /** One row: the variable, the property it sets, the default (null: no default, the variable is required). */
    record Entry(String variable, String property, String defaultValue, boolean required) {}

    @Test
    @DisplayName("4.1: справочник переменных совпадает с конфигурацией, которую читает сервер")
    void referenceMatchesTheConfiguration() throws IOException {
        String generated = generate();
        String document = Files.readString(COMMITTED, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = document.indexOf(START);
        int end = document.indexOf(END);
        assertThat(start)
                .as("markers " + START + " and " + END + " in " + COMMITTED)
                .isNotNegative();
        assertThat(end).isGreaterThan(start);
        String expected = document.substring(0, start + START.length()) + "\n" + generated + document.substring(end);
        if (Boolean.getBoolean("config.reference.update")) {
            Files.writeString(COMMITTED, expected, StandardCharsets.UTF_8);
            return;
        }
        assertThat(document)
                .as("the configuration changed: regenerate " + COMMITTED + " with -Dconfig.reference.update=true")
                .isEqualTo(expected);
    }

    static String generate() throws IOException {
        Map<String, Entry> entries = new TreeMap<>();
        yamlEntries().forEach(entry -> entries.put(entry.property(), entry));
        for (Map.Entry<String, String> key : boundKeys().entrySet()) {
            entries.putIfAbsent(key.getKey(), derived(key.getKey(), key.getValue()));
        }
        for (Map.Entry<String, String> key : codeKeys().entrySet()) {
            entries.putIfAbsent(key.getKey(), derived(key.getKey(), key.getValue()));
        }
        StringBuilder out = new StringBuilder();
        section(out, "Продукт (`smc.*`)", entries, key -> key.startsWith("smc."));
        section(out, "Хранилище (`warehouse.*`)", entries, key -> key.startsWith("warehouse."));
        section(
                out,
                "Платформа (Spring Boot и общие)",
                entries,
                key -> !key.startsWith("smc.") && !key.startsWith("warehouse."));
        out.append("### Секреты, которые вне профилей dev и test не могут сохранять значение по умолчанию\n\n");
        for (DefaultSecretsGuard.Secret secret : DefaultSecretsGuard.SECRETS) {
            out.append("- `").append(secret.variable()).append('`');
            if (secret.composeVariable() != null) {
                out.append(" (в `.env` для Compose — `")
                        .append(secret.composeVariable())
                        .append("`)");
            }
            out.append(" — `").append(secret.property()).append("`\n");
        }
        return out.append('\n').toString();
    }

    private static void section(StringBuilder out, String title, Map<String, Entry> entries, Predicate<String> filter) {
        List<Entry> rows = entries.values().stream()
                .filter(entry -> filter.test(entry.property()))
                .sorted((a, b) -> a.variable().compareTo(b.variable()))
                .toList();
        out.append("### ").append(title).append("\n\n");
        out.append("| Переменная окружения | Свойство | По умолчанию |\n|---|---|---|\n");
        for (Entry row : rows) {
            out.append("| `")
                    .append(row.variable())
                    .append("` | `")
                    .append(row.property())
                    .append("` | ")
                    .append(cell(row))
                    .append(" |\n");
        }
        out.append('\n');
    }

    private static String cell(Entry row) {
        if (row.required()) return "обязательна";
        if (row.defaultValue() == null) return "—";
        if (row.defaultValue().isEmpty()) return "пусто";
        return "`" + row.defaultValue().replace("|", "\\|") + "`";
    }

    /** The leaves of application.yml that are set by an environment variable or live under smc / warehouse. */
    private static List<Entry> yamlEntries() throws IOException {
        var document = (EnumerablePropertySource<?>) new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst();
        Map<String, List<String>> values = new TreeMap<>();
        for (String key : document.getPropertyNames()) {
            String property = LIST_INDEX.matcher(key).replaceAll("");
            values.computeIfAbsent(property, ignored -> new ArrayList<>())
                    .add(String.valueOf(document.getProperty(key)));
        }
        List<Entry> entries = new ArrayList<>();
        values.forEach((property, raw) -> {
            String value = String.join(",", raw);
            Matcher placeholder = PLACEHOLDER.matcher(value);
            if (placeholder.matches()) {
                String fallback = placeholder.group(2);
                entries.add(new Entry(placeholder.group(1), property, fallback, fallback == null));
            } else if (property.startsWith("smc.") || property.startsWith("warehouse.")) {
                entries.add(new Entry(variableOf(property), property, value, false));
            }
        });
        return entries;
    }

    /** Keys of the {@code @ConfigurationProperties} classes, with their {@code @DefaultValue} (null when none). */
    private static Map<String, String> boundKeys() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        Map<String, String> keys = new TreeMap<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.smartup24.cms")) {
            Class<?> type = ClassUtils.resolveClassName(String.valueOf(candidate.getBeanClassName()), null);
            ConfigurationProperties annotation = type.getAnnotation(ConfigurationProperties.class);
            String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
            collect(type, prefix, keys);
        }
        return keys;
    }

    private static void collect(Class<?> type, String prefix, Map<String, String> keys) {
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                String key = prefix + "." + kebab(component.getName());
                if (component.getType().isRecord() && component.getType().getEnclosingClass() != null) {
                    collect(component.getType(), key, keys);
                } else {
                    DefaultValue fallback = component.getAnnotation(DefaultValue.class);
                    keys.put(
                            key,
                            fallback == null || fallback.value().length == 0
                                    ? null
                                    : String.join(",", fallback.value()));
                }
            }
            return;
        }
        for (PropertyDescriptor descriptor : BeanUtils.getPropertyDescriptors(type)) {
            if (descriptor.getWriteMethod() != null) {
                keys.put(prefix + "." + kebab(descriptor.getName()), null);
            }
        }
    }

    /** Keys the code reads through {@code @Value} placeholders or {@code @ConditionalOnProperty}. */
    private static Map<String, String> codeKeys() throws IOException {
        Map<String, String> keys = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher value = VALUE_KEY.matcher(source);
                while (value.find()) {
                    keys.putIfAbsent(value.group(1), value.group(2));
                }
                Matcher condition = CONDITION_KEY.matcher(source);
                while (condition.find()) {
                    keys.putIfAbsent(condition.group(1), "true");
                }
            }
        }
        return keys;
    }

    private static Entry derived(String property, String defaultValue) {
        return new Entry(variableOf(property), property, defaultValue, false);
    }

    /** The environment variable relaxed binding reads for a property: dots and dashes become underscores. */
    static String variableOf(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
