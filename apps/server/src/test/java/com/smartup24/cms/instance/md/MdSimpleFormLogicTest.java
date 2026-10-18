package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.md.service.MdSimpleFormLogic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Format 5 of a simple form (ADR-0042): the formulas of the fields and the rules, each checked where it stands — a
 * computed field is optional and has nothing else, a default formula replaces a fixed default, {@code requiredIf}
 * makes an optional field required, a condition is a yes/no and a value gives its field's type — with the reason and
 * the position of the first problem at the formula's address.
 */
class MdSimpleFormLogicTest {
    private static final Path SAMPLES = Path.of("src/test/resources/studio/golden/stored-declarations-v5.json");

    private final JsonMapper json = JsonMapper.shared();
    private final MdSimpleFormDeclarations declarations = new MdSimpleFormDeclarations(json);

    @Test
    void theReferenceOrderReadsBackWithItsFormulasAndRules() throws Exception {
        var declaration = declarations.prepare(bytes(valid())).declaration();
        assertThat(declaration.formatVersion()).isEqualTo(5);
        assertThat(declaration.rules()).extracting(rule -> rule.key()).containsExactly("maxdiscount", "positive");
        assertThat(declaration.fields().get(3).formula()).startsWith("round(");
        assertThat(declaration.fields().get(5).hasLogic()).isTrue();
        assertThat(declaration.fields().getFirst().hasLogic()).isFalse();
    }

    @Test
    void anEarlierFormatKnowsNoFormulasOrRules() throws Exception {
        ObjectNode ruled = valid().put("formatVersion", 4);
        assertThat(problem(ruled)).isEqualTo("declaration:simple_form_unknown_property");
        ObjectNode computed = valid().put("formatVersion", 4);
        computed.remove("rules");
        assertThat(problem(computed)).isEqualTo("fields[3]:simple_form_unknown_property");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "0 | formula | qty + 1 | fields[0].formula:simple_form_logic",
                "6 | formula | 'new' | fields[6].formula:simple_form_logic",
                "4 | defaultFormula | today() | fields[4].defaultFormula:simple_form_logic",
                "3 | formula | '\"x\"' | fields[3].formula:formula_result",
                "5 | visibleIf | isEmpty(reason) | fields[5].visibleIf:formula_self",
                "5 | visibleIf | qty | fields[5].visibleIf:formula_result",
                "1 | requiredIf | qty > 1 | fields[1].requiredIf:simple_form_logic",
                "0 | requiredIf | qty > 1 | fields[0].requiredIf:simple_form_logic",
                "3 | formula | legacy + 1 | fields[3].formula:formula_unknown_field",
                "3 | formula | qty * | fields[3].formula:formula_syntax",
                "7 | readonlyIf | status = 1 | fields[7].readonlyIf:formula_type"
            })
    void aFormulaIsCheckedWhereItStands(int index, String key, String formula, String expected) throws Exception {
        ObjectNode declaration = valid();
        ObjectNode field = (ObjectNode) declaration.withArray("fields").get(index);
        field.put(key, formula);
        if (index == 4) field.put("defaultValue", "2026-01-01");
        assertThat(problem(declaration)).isEqualTo(expected);
    }

    @Test
    void theComputedFieldsMayNotReadEachOtherInACycle() throws Exception {
        ObjectNode declaration = valid();
        declaration.remove("layout");
        ArrayNode fields = declaration.withArray("fields");
        fields.add(number("aa", "bb + 1"));
        fields.add(number("bb", "aa * 2"));
        ApiException refused = refused(declaration);
        FieldErrorItem cycle = refused.getFieldErrors().getFirst();
        assertThat(cycle.field() + ":" + cycle.code()).isEqualTo("fields[10].formula:formula_cycle");
        assertThat(cycle.params()).containsEntry("name", "aa").containsEntry("position", 1);
        ObjectNode self = valid();
        self.remove("layout");
        self.withArray("fields").add(number("aa", "aa + 1"));
        assertThat(problem(self)).isEqualTo("fields[9].formula:formula_cycle");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "empty | rules:simple_form_limit",
                "many | rules:simple_form_limit",
                "duplicate | rules[1].key:simple_form_duplicate",
                "key | rules[0].key:simple_form_identifier",
                "field | rules[0].field:simple_form_unsupported",
                "hiddenField | rules[0].field:simple_form_unsupported",
                "message | rules[0].message.ru:simple_form_label",
                "noFormula | rules[0].formula:simple_form_missing",
                "number | rules[0].formula:formula_result",
                "blank | rules[0].formula:simple_form_formula",
                "long | rules[0].formula:simple_form_formula",
                "control | rules[0].formula:simple_form_formula",
                "unknown | rules[0]:simple_form_unknown_property"
            })
    void theRulesAreBoundedAndChecked(String change, String expected) throws Exception {
        ObjectNode declaration = valid();
        ArrayNode rules = declaration.withArray("rules");
        ObjectNode first = (ObjectNode) rules.get(0);
        Map<String, Consumer<ObjectNode>> changes = Map.ofEntries(
                Map.entry("empty", root -> root.putArray("rules")),
                Map.entry("many", root -> {
                    for (int i = 0; i < 19; i++) rules.add(first.deepCopy().put("key", "r" + i));
                }),
                Map.entry("duplicate", root -> ((ObjectNode) rules.get(1)).put("key", "maxdiscount")),
                Map.entry("key", root -> first.put("key", "Bad")),
                Map.entry("field", root -> first.put("field", "nosuch")),
                Map.entry("hiddenField", root -> first.put("field", "legacy")),
                Map.entry("message", root -> first.withObject("message").put("ru", "x".repeat(201))),
                Map.entry("noFormula", root -> first.remove("formula")),
                Map.entry("number", root -> first.put("formula", "qty + 1")),
                Map.entry("blank", root -> first.put("formula", "  ")),
                Map.entry("long", root -> first.put("formula", "1".repeat(1001))),
                Map.entry("control", root -> first.put("formula", "qty > 1" + (char) 7)),
                Map.entry("unknown", root -> first.put("extra", true)));
        changes.get(change).accept(declaration);
        assertThat(problem(declaration)).isEqualTo(expected);
    }

    @Test
    void theEditorCheckGivesTheTypeOrTheProblem() {
        Map<String, FormulaType> types = Map.of("qty", FormulaType.NUMBER, "reason", FormulaType.TEXT);
        assertThat(MdSimpleFormLogic.check("qty * 2", types, "number", null).type())
                .isEqualTo(FormulaType.NUMBER);
        assertThat(MdSimpleFormLogic.check("qty * 2", types, null, null)
                        .problem()
                        .reason())
                .isEqualTo("result");
        assertThat(MdSimpleFormLogic.check("isEmpty(reason)", types, null, "reason")
                        .problem()
                        .reason())
                .isEqualTo("self");
        assertThat(MdSimpleFormLogic.check("nosuch", types, null, null)
                        .problem()
                        .name())
                .isEqualTo("nosuch");
        assertThat(MdSimpleFormLogic.fits("textarea", FormulaType.OPTION)).isTrue();
        assertThat(MdSimpleFormLogic.fits("time", FormulaType.NULL)).isTrue();
        assertThat(MdSimpleFormLogic.fits("datetime", FormulaType.DATE)).isFalse();
    }

    private ObjectNode number(String id, String formula) {
        ObjectNode field = json.createObjectNode()
                .put("id", id)
                .put("type", "number")
                .put("required", false)
                .put("listed", false)
                .put("scale", 0)
                .put("formula", formula);
        field.putObject("labels").put("ru", id).put("uz", id).put("en", id);
        return field;
    }

    private ObjectNode valid() throws Exception {
        return (ObjectNode) json.readTree(Files.readString(SAMPLES)).get(0).get("declaration");
    }

    private String problem(ObjectNode declaration) {
        FieldErrorItem first = refused(declaration).getFieldErrors().getFirst();
        return first.field() + ":" + first.code();
    }

    private ApiException refused(ObjectNode declaration) {
        try {
            declarations.prepare(bytes(declaration));
        } catch (ApiException refused) {
            return refused;
        }
        throw new AssertionError("accepted: " + declaration);
    }

    private byte[] bytes(ObjectNode declaration) {
        return json.writeValueAsBytes(declaration);
    }
}
