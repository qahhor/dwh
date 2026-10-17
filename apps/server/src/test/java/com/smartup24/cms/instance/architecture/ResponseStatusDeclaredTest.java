package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

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
 * only 201, 202 or 204 declares it. One success status per handler is the rule; the only exception is the pair 201 and
 * 200 of a create-only operation whose exact repeat creates nothing (ADR-0038, decision 12), and only for a handler
 * named in {@link #TWO_STATUSES} with its reason: it declares 201 with an explicit {@code @ResponseStatus} and both
 * with {@code @ApiResponse}, so the description still states every status it answers. A status built in a helper is
 * not visible to this scan and is checked in review.
 */
class ResponseStatusDeclaredTest {

    /** The handlers allowed to answer both 201 and 200, by class and method, with the reason. */
    static final Map<String, String> TWO_STATUSES = Map.of(
            "StudioFormController#activate",
            "ADR-0038, decision 12: an exact repeat of a served form creates nothing and answers 200");

    private static final Set<String> PAIR = Set.of("CREATED", "OK");

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
        Set<String> twoStatuses = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (source.contains("@RestController")) {
                    twoStatuses.addAll(
                            check(MAIN.relativize(file).toString().replace('\\', '/'), source, wrong, TWO_STATUSES));
                }
            }
        }
        assertThat(wrong)
                .as("handlers whose declared status differs from what they answer")
                .isEmpty();
        assertThat(twoStatuses)
                .as("every allowed handler still answers two statuses, or its entry is removed")
                .containsExactlyInAnyOrderElementsOf(TWO_STATUSES.keySet());
    }

    /** Checks the handlers of one source and returns those that answer two statuses, by class and method. */
    private static Set<String> check(String file, String source, List<String> wrong, Map<String, String> allowed) {
        Set<String> twoStatuses = new TreeSet<>();
        String type = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
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
            boolean explicit = declared.find();
            String declaredStatus = explicit ? declared.group(1) : "OK";
            String key = type + "#" + handler.group(1);
            String where = file + "#" + handler.group(1);
            if (answered.size() > 1) {
                twoStatuses.add(key);
                String problem = twoStatusesProblem(key, answered, explicit, declaredStatus, annotations, allowed);
                if (problem != null) wrong.add(where + " answers " + answered + ": " + problem);
            } else if (answered.size() == 1 && !answered.contains(declaredStatus)) {
                wrong.add(where + " answers " + answered + " but declares " + declaredStatus);
            }
        }
        return twoStatuses;
    }

    private static String twoStatusesProblem(
            String key,
            Set<String> answered,
            boolean explicit,
            String declaredStatus,
            String annotations,
            Map<String, String> allowed) {
        if (!answered.equals(PAIR)) return "only the pair 201 and 200 may be answered by one handler";
        if (!allowed.containsKey(key))
            return "one success status per handler, or an entry in TWO_STATUSES with a reason";
        if (!explicit || !"CREATED".equals(declaredStatus)) return "declare 201 with an explicit @ResponseStatus";
        if (!answered.stream().allMatch(status -> described(annotations, status)))
            return "declare each status with @ApiResponse";
        return null;
    }

    private static boolean described(String annotations, String status) {
        return Pattern.compile("@ApiResponse\\(\\s*responseCode\\s*=\\s*\"" + CODES.get(status) + "\"")
                .matcher(annotations)
                .find();
    }

    private static final String REPEAT = " {\n        if (repeat) return ResponseEntity.ok().body(x);\n"
            + "        return ResponseEntity.created(uri).body(x);\n    }\n";
    private static final String DESCRIBED = "    @ApiResponses({\n        @ApiResponse(responseCode = \"201\"),\n"
            + "        @ApiResponse(responseCode = \"200\")\n    })\n";
    private static final Map<String, String> ALLOWED = Map.of("A#create", "a create-only operation");

    @Test
    @DisplayName("3.4: 201 and 200 pass only for an allowed handler with @ResponseStatus(CREATED) and @ApiResponse")
    void twoStatusesNeedTheirAllowanceAndDescription() {
        String described = controller(DESCRIBED + "    @ResponseStatus(HttpStatus.CREATED)\n", REPEAT);
        assertThat(problems(described, ALLOWED)).isEmpty();
        assertThat(problems(described, Map.of())).singleElement(STRING).contains("TWO_STATUSES");
        assertThat(problems(controller("    @ResponseStatus(HttpStatus.CREATED)\n", REPEAT), ALLOWED))
                .singleElement(STRING)
                .contains("@ApiResponse");
    }

    @Test
    @DisplayName("3.4: two statuses without an explicit @ResponseStatus fail, even when described and allowed")
    void twoStatusesNeedAnExplicitResponseStatus() {
        assertThat(problems(controller(DESCRIBED, REPEAT), ALLOWED))
                .singleElement(STRING)
                .contains("explicit @ResponseStatus");
        String declaredOk = controller(DESCRIBED + "    @ResponseStatus(HttpStatus.OK)\n", REPEAT);
        assertThat(problems(declaredOk, ALLOWED)).hasSize(1);
    }

    @Test
    @DisplayName("3.4: three statuses, or 201 with 204, never pass")
    void onlyThePairOfCreatedAndOkPasses() {
        String three = " {\n        if (a) return ResponseEntity.ok().body(x);\n"
                + "        if (b) return ResponseEntity.noContent().build();\n"
                + "        return ResponseEntity.created(uri).body(x);\n    }\n";
        String createdOrNone = " {\n        if (b) return ResponseEntity.noContent().build();\n"
                + "        return ResponseEntity.created(uri).body(x);\n    }\n";
        String annotations = DESCRIBED + "    @ResponseStatus(HttpStatus.CREATED)\n";
        for (String body : List.of(three, createdOrNone)) {
            assertThat(problems(controller(annotations, body), ALLOWED))
                    .singleElement(STRING)
                    .contains("only the pair 201 and 200");
        }
    }

    private static String controller(String annotations, String body) {
        return "@RestController\nclass A {\n\n" + annotations + "    public ResponseEntity<X> create()" + body + "}\n";
    }

    private static List<String> problems(String source, Map<String, String> allowed) {
        List<String> wrong = new ArrayList<>();
        assertThat(check("pkg/A.java", source, wrong, allowed)).containsExactly("A#create");
        return wrong;
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
