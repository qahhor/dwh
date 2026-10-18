package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The document parts of simple-form format 6 (ADR-0043), checked when a package is prepared: the tables of lines and
 * their columns, the number's pattern and period, the statuses and transitions and the formulas of all of them. Each
 * case changes the stored reference order in one place and expects the address and the rule of its problem.
 */
class MdSimpleFormDocumentsTest {
    private static final Path ORDER = Path.of("src/test/resources/studio/golden/stored-declarations-v6.json");

    private final ObjectMapper json = JsonMapper.shared();
    private final MdSimpleFormDeclarations declarations = new MdSimpleFormDeclarations(json);

    @Test
    void theReferenceOrderIsAccepted() throws Exception {
        var prepared = declarations.prepare(bytes(order()));
        assertThat(prepared.declaration().lines()).hasSize(1);
        assertThat(prepared.declaration().workflow().transitions()).hasSize(3);
        assertThat(prepared.declaration().entity().numbering().pattern()).isEqualTo("ЗК-{YYYY}-{NNNNNN}");
    }

    @Test
    void theTablesOfLinesAreBoundedAndKeyedApartFromTheFields() throws Exception {
        refused(root -> lines(root).removeAll(), "lines", "simple_form_limit");
        refused(root -> lines(root).add(table(root).deepCopy()), "lines[1].key", "simple_form_duplicate");
        refused(root -> table(root).put("key", "client"), "lines[0].key", "simple_form_duplicate");
        refused(root -> table(root).put("key", "Items"), "lines[0].key", "simple_form_identifier");
        refused(root -> table(root).put("maxRows", 501), "lines[0].maxRows", "simple_form_limit");
        refused(root -> table(root).put("extra", 1), "lines[0]", "simple_form_unknown_property");
        refused(root -> column(root, 0).put("id", "id"), "lines[0].fields[0].id", "simple_form_identifier");
        refused(root -> column(root, 1).put("id", "product"), "lines[0].fields[1].id", "simple_form_duplicate");
        refused(root -> column(root, 1).put("type", "markdown"), "lines[0].fields[1].type", "simple_form_unsupported");
        refused(
                root -> column(root, 1).put("maxLength", 10),
                "lines[0].fields[1].maxLength",
                "simple_form_unsupported");
        refused(root -> column(root, 4).put("scale", 2), "lines[0].fields[4].scale", "simple_form_unsupported");
        refused(root -> ((ArrayNode) table(root).get("fields")).removeAll(), "lines[0].fields", "simple_form_limit");
    }

    @Test
    void aComputedColumnReadsItsRowAndTheEnteredHeaderWithoutACycle() throws Exception {
        refused(root -> column(root, 3).put("required", true), "lines[0].fields[3].formula", "simple_form_logic");
        refused(root -> column(root, 0).put("formula", "1"), "lines[0].fields[0].formula", "simple_form_logic");
        refused(
                root -> column(root, 3).put("formula", "qty * doc.total"),
                "lines[0].fields[3].formula",
                "formula_unknown_field");
        refused(
                root -> {
                    column(root, 3).put("formula", "price");
                    column(root, 2).put("formula", "amount").put("required", false);
                },
                "lines[0].fields[3].formula",
                "formula_cycle");
        refused(root -> field(root, 2).put("formula", "sum(lines.items.note)"), "fields[2].formula", "formula_type");
        refused(
                root -> ((ObjectNode) root.get("rules").get(0)).put("formula", "lines.items.qty > 1"),
                "rules[0].formula",
                "formula_type");
    }

    @Test
    void theNumberHasOneCounterAndAPeriodItsPatternNames() throws Exception {
        for (String pattern : List.of("NO", "A-{N}{N}", "A-{NNNNNNNNNNNNN}", "A{-{NNN}", "A-{YYYY}-{XX}-{NN}")) {
            refused(
                    root -> numbering(root).put("pattern", pattern),
                    "entity.numbering.pattern",
                    "simple_form_numbering");
        }
        refused(
                root -> numbering(root).put("pattern", "ZK-{NNNN}"),
                "entity.numbering.pattern",
                "simple_form_numbering");
        refused(
                root -> numbering(root).put("pattern", "ZK-{YYYY}-{NNNN}").put("reset", "month"),
                "entity.numbering.pattern",
                "simple_form_numbering");
        refused(root -> numbering(root).put("reset", "week"), "entity.numbering.reset", "simple_form_unsupported");
        refused(root -> numbering(root).put("assignOn", "nosuch"), "entity.numbering.assignOn", "simple_form_workflow");
        var monthly = order();
        numbering(monthly)
                .put("pattern", "{YY}{MM}-{NNN}")
                .put("reset", "month")
                .remove("assignOn");
        assertThat(declarations
                        .prepare(bytes(monthly))
                        .declaration()
                        .entity()
                        .numbering()
                        .reset())
                .isEqualTo("month");
        var plain = order();
        ((ObjectNode) plain.get("entity")).remove("numbering");
        plain.remove("workflow");
        assertThat(declarations.prepare(bytes(plain)).declaration().workflow()).isNull();
    }

    @Test
    void theStatusesStartInAWorkingOneAndTheTransitionsLeaveOnlyWorkingOnes() throws Exception {
        refused(root -> state(root, 0).put("terminal", true), "workflow.states[0].terminal", "simple_form_workflow");
        refused(root -> state(root, 1).put("terminal", false), "workflow.states[1].terminal", "simple_form_structure");
        refused(root -> state(root, 1).put("color", "pink"), "workflow.states[1].color", "simple_form_unsupported");
        refused(root -> state(root, 1).put("code", "draft"), "workflow.states[1].code", "simple_form_duplicate");
        refused(
                root -> ((ArrayNode) state(root, 3).get("locks")).add("nosuch"),
                "workflow.states[3].locks[2]",
                "simple_form_workflow");
        refused(
                root -> ((ArrayNode) transition(root, 1).get("from")).set(0, "approved"),
                "workflow.transitions[1].from[0]",
                "simple_form_workflow");
        refused(root -> transition(root, 1).put("to", "nosuch"), "workflow.transitions[1].to", "simple_form_workflow");
        refused(root -> transition(root, 1).put("to", "pending"), "workflow.transitions[1].to", "simple_form_workflow");
        refused(
                root -> transition(root, 1).put("code", "update"),
                "workflow.transitions[1].code",
                "simple_form_identifier");
        refused(
                root -> transition(root, 2).put("code", "approve"),
                "workflow.transitions[2].code",
                "simple_form_duplicate");
        refused(
                root -> transition(root, 1).put("comment", "maybe"),
                "workflow.transitions[1].comment",
                "simple_form_unsupported");
        refused(
                root -> transition(root, 1).put("notify", false),
                "workflow.transitions[1].notify",
                "simple_form_structure");
        refused(
                root -> transition(root, 0).put("condition", "total + 1"),
                "workflow.transitions[0].condition",
                "formula_result");
        refused(
                root -> ((ArrayNode) workflow(root).get("states")).remove(3),
                "workflow.transitions[0].from[1]",
                "simple_form_workflow");
    }

    private void refused(Consumer<ObjectNode> change, String at, String code) throws Exception {
        ObjectNode root = order();
        change.accept(root);
        assertThatThrownBy(() -> declarations.prepare(bytes(root)))
                .as("%s %s", at, code)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.getFieldErrors())
                                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                                .contains(org.assertj.core.groups.Tuple.tuple(at, code)));
    }

    private ObjectNode order() throws Exception {
        JsonNode samples = json.readTree(Files.readString(ORDER));
        return (ObjectNode) samples.get(0).get("declaration").deepCopy();
    }

    private byte[] bytes(JsonNode root) {
        return json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    private static ArrayNode lines(ObjectNode root) {
        return (ArrayNode) root.get("lines");
    }

    private static ObjectNode table(ObjectNode root) {
        return (ObjectNode) lines(root).get(0);
    }

    private static ObjectNode column(ObjectNode root, int index) {
        return (ObjectNode) table(root).get("fields").get(index);
    }

    private static ObjectNode field(ObjectNode root, int index) {
        return (ObjectNode) root.get("fields").get(index);
    }

    private static ObjectNode numbering(ObjectNode root) {
        return (ObjectNode) root.get("entity").get("numbering");
    }

    private static ObjectNode workflow(ObjectNode root) {
        return (ObjectNode) root.get("workflow");
    }

    private static ObjectNode state(ObjectNode root, int index) {
        return (ObjectNode) workflow(root).get("states").get(index);
    }

    private static ObjectNode transition(ObjectNode root, int index) {
        return (ObjectNode) workflow(root).get("transitions").get(index);
    }
}
