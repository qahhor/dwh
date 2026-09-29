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
 * constructors and factories) is a key of the i18n catalogs, present in Russian, English and Uzbek. A text passed
 * through a variable or a constant is not visible to this scan and is checked in review.
 */
class ErrorTextsTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path CATALOGS = Path.of("src/main/resources/i18n");
    private static final List<String> LANGUAGES = List.of("ru", "en", "uz");

    /** A constructor or factory call; permissionDenied takes the right's form and action, not a text. */
    private static final Pattern CALL =
            Pattern.compile("(?:new\\s+ApiException|ApiException\\.(?!permissionDenied\\b)\\w+)\\s*\\(");

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
            }
        }
        return new Scan(sentences, keys);
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
