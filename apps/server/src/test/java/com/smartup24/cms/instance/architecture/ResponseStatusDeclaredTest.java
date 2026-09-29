package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 3.4: the API description states the status a handler really answers. springdoc reads
 * {@code @ResponseStatus}; it cannot see the status inside a returned {@code ResponseEntity}. So a handler that answers
 * only 201, 202 or 204 declares it, and one that mixes a success status with 200 does not exist. A status built in a
 * helper is not visible to this scan and is checked in review.
 */
class ResponseStatusDeclaredTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern HANDLER = Pattern.compile("\\n    public ResponseEntity<[^\\n]*?\\b(\\w+)\\(");

    private static final Map<String, Pattern> STATUSES = Map.of(
            "NO_CONTENT", Pattern.compile("noContent\\(\\)"),
            "CREATED", Pattern.compile("HttpStatus\\.CREATED|Created\\.at\\(|ResponseEntity\\.created\\("),
            "ACCEPTED", Pattern.compile("accepted\\(\\)|HttpStatus\\.ACCEPTED"),
            "OK", Pattern.compile("ResponseEntity\\s*\\.\\s*ok\\s*\\(|HttpStatus\\.OK\\b"));

    @Test
    @DisplayName("3.4: a handler answering 201, 202 or 204 declares it with @ResponseStatus")
    void successStatusIsDeclared() throws IOException {
        List<String> wrong = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (source.contains("@RestController")) {
                    check(MAIN.relativize(file).toString().replace('\\', '/'), source, wrong);
                }
            }
        }
        assertThat(wrong)
                .as("handlers whose declared status differs from what they answer")
                .isEmpty();
    }

    private static void check(String file, String source, List<String> wrong) {
        Matcher handler = HANDLER.matcher(source);
        while (handler.find()) {
            int open = source.indexOf('{', handler.end());
            String body = source.substring(open, closing(source, open));
            Set<String> answered = new TreeSet<>();
            STATUSES.forEach((status, pattern) -> {
                if (pattern.matcher(body).find()) {
                    answered.add(status);
                }
            });
            int annotationsStart = source.lastIndexOf("\n\n", handler.start());
            String annotations = source.substring(annotationsStart, handler.start() + 1);
            Matcher declared =
                    Pattern.compile("@ResponseStatus\\(HttpStatus\\.(\\w+)\\)").matcher(annotations);
            String declaredStatus = declared.find() ? declared.group(1) : "OK";
            String where = file + "#" + handler.group(1);
            if (answered.size() > 1) {
                wrong.add(where + " answers " + answered + ": one success status per handler");
            } else if (answered.size() == 1 && !answered.contains(declaredStatus)) {
                wrong.add(where + " answers " + answered + " but declares " + declaredStatus);
            }
        }
    }

    private static int closing(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return source.length();
    }
}
