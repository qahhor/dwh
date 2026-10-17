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
 * only 201, 202 or 204 declares it. A handler that answers two success statuses — an exact repeat of a create-only
 * operation answers 200 where a new resource answers 201 (ADR-0038) — declares the main one with
 * {@code @ResponseStatus} and each of them with {@code @ApiResponse}, so the description still states every status it
 * answers. A status built in a helper is not visible to this scan and is checked in review.
 */
class ResponseStatusDeclaredTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern HANDLER = Pattern.compile("\\n    public ResponseEntity<[^\\n]*?\\b(\\w+)\\(");

    private static final Map<String, Pattern> STATUSES = Map.of(
            "NO_CONTENT", Pattern.compile("noContent\\(\\)"),
            "CREATED", Pattern.compile("HttpStatus\\.CREATED|Created\\.at\\(|ResponseEntity\\.created\\("),
            "ACCEPTED", Pattern.compile("accepted\\(\\)|HttpStatus\\.ACCEPTED"),
            "OK", Pattern.compile("ResponseEntity\\s*\\.\\s*ok\\s*\\(|HttpStatus\\.OK\\b"));

    private static final Map<String, String> CODES =
            Map.of("NO_CONTENT", "204", "CREATED", "201", "ACCEPTED", "202", "OK", "200");

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
                boolean described = answered.contains(declaredStatus)
                        && answered.stream().allMatch(status -> described(annotations, status));
                if (!described) {
                    wrong.add(where + " answers " + answered
                            + ": one success status per handler, or each one declared with @ApiResponse");
                }
            } else if (answered.size() == 1 && !answered.contains(declaredStatus)) {
                wrong.add(where + " answers " + answered + " but declares " + declaredStatus);
            }
        }
    }

    private static boolean described(String annotations, String status) {
        return Pattern.compile("@ApiResponse\\(\\s*responseCode\\s*=\\s*\"" + CODES.get(status) + "\"")
                .matcher(annotations)
                .find();
    }

    @Test
    @DisplayName("3.4: two success statuses pass only when each of them is declared with @ApiResponse")
    void twoStatusesNeedTheirDescription() {
        String body = " {\n        if (repeat) return ResponseEntity.ok().body(x);\n"
                + "        return ResponseEntity.created(uri).body(x);\n    }\n";
        String bare = "@RestController\nclass A {\n\n    @ResponseStatus(HttpStatus.CREATED)\n"
                + "    public ResponseEntity<X> create()" + body + "}\n";
        String described = "@RestController\nclass A {\n\n    @ApiResponses({\n"
                + "        @ApiResponse(responseCode = \"201\"),\n        @ApiResponse(responseCode = \"200\")\n"
                + "    })\n    @ResponseStatus(HttpStatus.CREATED)\n"
                + "    public ResponseEntity<X> create()" + body + "}\n";
        List<String> wrong = new ArrayList<>();
        check("A.java", bare, wrong);
        assertThat(wrong).hasSize(1);
        wrong.clear();
        check("A.java", described, wrong);
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("3.6: a handler that answers ETag itself is marked @AnswersRevision, so the description declares it")
    void etagIsDeclared() throws IOException {
        List<String> wrong = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (!source.contains("@RestController")) {
                    continue;
                }
                Matcher handler = HANDLER.matcher(source);
                while (handler.find()) {
                    int open = source.indexOf('{', handler.end());
                    boolean answers =
                            source.substring(open, closing(source, open)).contains(".eTag(");
                    String annotations = source.substring(source.lastIndexOf("\n\n", handler.start()), handler.start());
                    boolean marked = annotations.contains("@AnswersRevision");
                    if (answers != marked) {
                        wrong.add(MAIN.relativize(file).toString().replace('\\', '/') + "#" + handler.group(1)
                                + (answers ? " sets ETag without @AnswersRevision" : " is marked but sets no ETag"));
                    }
                }
            }
        }
        assertThat(wrong)
                .as("handlers whose ETag the description would miss or invent")
                .isEmpty();
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
