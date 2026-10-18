package com.smartup24.cms.instance.common.formula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shared conformance cases of the formula language (ADR-0042), {@code docs/api/formula-conformance.json}: the web
 * interprets the same checked trees with the same values and must agree. Every accepted case's tree is stored in the
 * file, so a change of the checker shows in the diff; {@code -Dformula.update=true} writes the trees anew.
 */
class FormulaConformanceTest {
    private static final Path CASES = Path.of("../../docs/api/formula-conformance.json");
    private static final ObjectMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Test
    void everyCaseGivesItsResultFailureOrProblem() throws Exception {
        ObjectNode document = (ObjectNode) JSON.readTree(Files.readString(CASES, StandardCharsets.UTF_8));
        Map<String, FormulaType> fields = new LinkedHashMap<>();
        document.path("fields")
                .properties()
                .forEach(entry -> fields.put(
                        entry.getKey(), FormulaType.of(entry.getValue().asString())));
        FormulaScope scope = new FormulaScope(
                ZoneId.of(document.path("scope").path("zone").asString()),
                Instant.parse(document.path("scope").path("now").asString()));
        boolean update = Boolean.getBoolean("formula.update");
        List<String> stale = new ArrayList<>();
        for (JsonNode node : document.path("cases")) {
            ObjectNode item = (ObjectNode) node;
            String formula = item.path("formula").asString();
            if (item.has("error")) {
                refused(formula, fields, item.path("error"));
                continue;
            }
            Formula checked = Formula.compile(formula, fields);
            JsonNode tree = JSON.valueToTree(checked.root());
            if (!tree.equals(item.path("ast"))) {
                if (update) item.set("ast", tree);
                else stale.add(formula);
            }
            evaluated(item, checked, scope);
        }
        if (update) {
            Files.writeString(
                    CASES, JSON.writeValueAsString(document).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        }
        assertThat(stale)
                .as("checked trees differ from the file; run with -Dformula.update=true and review the diff")
                .isEmpty();
    }

    private static void refused(String formula, Map<String, FormulaType> fields, JsonNode expected) {
        assertThatThrownBy(() -> Formula.compile(formula, fields))
                .as(formula)
                .isInstanceOfSatisfying(FormulaException.class, problem -> {
                    assertThat(problem.reason())
                            .as(formula)
                            .isEqualTo(expected.path("reason").asString());
                    assertThat(problem.position())
                            .as(formula)
                            .isEqualTo(expected.path("position").asInt());
                    String name = expected.has("name") ? expected.path("name").asString() : null;
                    assertThat(problem.name()).as(formula).isEqualTo(name);
                });
    }

    private static void evaluated(JsonNode item, Formula checked, FormulaScope scope) {
        String formula = item.path("formula").asString();
        JsonNode values = item.path("values");
        if (item.has("failure")) {
            assertThatThrownBy(() -> checked.evaluate(key -> raw(values.get(key)), scope))
                    .as(formula)
                    .isInstanceOfSatisfying(
                            FormulaFailure.class,
                            failure -> assertThat(failure.reason())
                                    .as(formula)
                                    .isEqualTo(item.path("failure").asString()));
            return;
        }
        JsonNode result = item.path("result");
        if (result.isMissingNode()) fail("A case names its result, failure or problem: " + formula);
        assertThat(checked.type().wire())
                .as(formula)
                .isEqualTo(result.path("type").asString());
        Object value = checked.evaluate(key -> raw(values.get(key)), scope);
        String expected =
                result.path("value").isNull() ? null : result.path("value").asString();
        assertThat(value == null ? null : FormulaValues.text(value)).as(formula).isEqualTo(expected);
    }

    /** A value of the file as a client sends it: a text, a number's text, a yes/no; absent and null are null. */
    private static @Nullable Object raw(@Nullable JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.toString();
        return value.asString();
    }
}
