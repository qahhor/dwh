package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.error.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.1: the code that throws never writes a sentence. Every text given to {@link ApiException} (its
 * constructors and factories) is a key of the i18n catalogs, present in Russian, English and Uzbek. So is the text of
 * a field error: {@code FieldErrorItem.keyed} takes a key, and {@code new FieldErrorItem} never gets a literal text
 * (its written form is for bean validation's own message). A text passed through a variable or a constant is not
 * visible to this scan and is checked in review; a key literal anywhere in the code must be in the catalogs.
 */
class ErrorTextsTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path CATALOGS = Path.of("src/main/resources/i18n");
    private static final List<String> LANGUAGES = List.of("ru", "en", "uz");

    /** A constructor or factory call; permissionDenied takes the right's form and action, not a text. */
    private static final Pattern CALL =
            Pattern.compile("(?:new\\s+ApiException|ApiException\\.(?!permissionDenied\\b)\\w+)\\s*\\(");

    /** A written field error: its field and code may be literals, its text (third argument on) may not. */
    private static final Pattern FIELD_ERROR = Pattern.compile("new\\s+FieldErrorItem\\s*\\(");

    /** A keyed field error: the third argument is the key. */
    private static final Pattern KEYED_FIELD_ERROR = Pattern.compile("FieldErrorItem\\.keyed\\s*\\(");

    /** A string literal that is a whole error key ({@code "error.md.module_not_found"}). */
    private static final Pattern ERROR_KEY_LITERAL = Pattern.compile("\"(error(?:\\.[a-z0-9_]+)+)\"");

    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    /** What the scan found: per file, the sentences and the keys. */
    record Scan(Map<String, List<String>> sentences, Map<String, Set<String>> keys) {}

    @Test
    @DisplayName("3.1: texts given to ApiException are catalog keys, never sentences")
    void noSentences() throws IOException {
        assertThat(scan().sentences())
                .as("sentences instead of catalog keys (use error.<module>.<name>)")
                .isEmpty();
    }

    @Test
    @DisplayName("3.1: every key given to ApiException is in the Russian, English and Uzbek catalogs")
    void everyKeyIsInEveryCatalog() throws IOException {
        Scan scan = scan();
        Map<String, List<String>> missing = new TreeMap<>();
        for (String language : LANGUAGES) {
            Map<String, String> catalog = new ObjectMapper()
                    .readValue(CATALOGS.resolve(language + ".json").toFile(), new TypeReference<>() {});
            scan.keys()
                    .forEach((file, keys) -> keys.stream()
                            .filter(key -> !catalog.containsKey(key))
                            .forEach(key -> missing.computeIfAbsent(language, l -> new ArrayList<>())
                                    .add(key + " (" + file + ")")));
        }
        assertThat(missing).as("keys missing in a catalog").isEmpty();
    }

    @Test
    @DisplayName("3.1: a literal text in a field error is found, a keyed one is checked against the catalogs")
    void literalFieldErrorTextIsFound() {
        Map<String, List<String>> sentences = new TreeMap<>();
        Map<String, Set<String>> keys = new TreeMap<>();

        scanFieldErrors("""
                errors.add(new FieldErrorItem("name", "required", "Поле обязательно"));
                errors.add(new FieldErrorItem("sort", SORT, "not a sortable field: " + sort));
                errors.add(new FieldErrorItem(fe.getField(), fe.getCode(), fe.getDefaultMessage()));
                errors.add(FieldErrorItem.keyed("q", SEARCH, "search is too long"));
                errors.add(FieldErrorItem.keyed("q", SEARCH, "error.field.required", Map.of()));
                """, "Fixture.java", sentences, keys);

        assertThat(sentences.get("Fixture.java"))
                .containsExactly("\"Поле обязательно\"", "\"not a sortable field: \" + sort", "\"search is too long\"");
        assertThat(keys.get("Fixture.java")).containsExactly("error.field.required");
    }

    static Scan scan() throws IOException {
        Map<String, List<String>> sentences = new TreeMap<>();
        Map<String, Set<String>> keys = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String name = MAIN.relativize(file).toString().replace('\\', '/');
                Matcher call = CALL.matcher(source);
                while (call.find()) {
                    for (String argument : topLevelArguments(source, call.end())) {
                        String trimmed = argument.strip();
                        if (CALL.matcher(trimmed).find()) {
                            continue; // a nested call (requirePresent's supplier) is scanned as a call of its own
                        }
                        Matcher literal = LITERAL.matcher(trimmed);
                        if (!literal.find()) {
                            continue;
                        }
                        boolean whole = literal.start() == 0 && literal.end() == trimmed.length();
                        String text =
                                literal.group().substring(1, literal.group().length() - 1);
                        if (whole && ApiException.MESSAGE_KEY.matcher(text).matches()) {
                            keys.computeIfAbsent(name, n -> new TreeSet<>()).add(text);
                        } else if (!trimmed.startsWith("Map.of") && !trimmed.startsWith("List.of")) {
                            sentences
                                    .computeIfAbsent(name, n -> new ArrayList<>())
                                    .add(trimmed);
                        }
                    }
                }
                scanFieldErrors(source, name, sentences, keys);
                Matcher key = ERROR_KEY_LITERAL.matcher(source);
                while (key.find()) {
                    keys.computeIfAbsent(name, n -> new TreeSet<>()).add(key.group(1));
                }
            }
        }
        return new Scan(sentences, keys);
    }

    /** Field errors: a keyed one names a catalog key, a written one never gets a literal text. */
    static void scanFieldErrors(
            String source, String name, Map<String, List<String>> sentences, Map<String, Set<String>> keys) {
        Matcher written = FIELD_ERROR.matcher(source);
        while (written.find()) {
            List<String> arguments = topLevelArguments(source, written.end());
            for (String argument : arguments.subList(Math.min(2, arguments.size()), arguments.size())) {
                if (LITERAL.matcher(argument).find()) {
                    sentences.computeIfAbsent(name, n -> new ArrayList<>()).add(argument.strip());
                }
            }
        }
        Matcher keyed = KEYED_FIELD_ERROR.matcher(source);
        while (keyed.find()) {
            List<String> arguments = topLevelArguments(source, keyed.end());
            if (arguments.size() < 3) {
                continue;
            }
            String key = arguments.get(2).strip();
            Matcher literal = LITERAL.matcher(key);
            if (!literal.find()) {
                continue; // a key passed through a variable (a helper's parameter): checked where it is written
            }
            String text = key.substring(1, key.length() - 1);
            if (literal.start() == 0
                    && literal.end() == key.length()
                    && ApiException.MESSAGE_KEY.matcher(text).matches()) {
                keys.computeIfAbsent(name, n -> new TreeSet<>()).add(text);
            } else {
                sentences.computeIfAbsent(name, n -> new ArrayList<>()).add(key);
            }
        }
    }

    /** The arguments of the call whose opening parenthesis ends at {@code start}, split at depth zero. */
    static List<String> topLevelArguments(String source, int start) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int from = start;
        int i = start;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"') {
                Matcher literal = LITERAL.matcher(source);
                if (literal.find(i) && literal.start() == i) {
                    i = literal.end();
                    continue;
                }
            }
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                if (depth == 0) {
                    arguments.add(source.substring(from, i));
                    return arguments;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(source.substring(from, i));
                from = i + 1;
            }
            i++;
        }
        return arguments;
    }
}
