package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormFileDtos.Files;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import java.nio.charset.StandardCharsets;
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
 * The file field of simple-form format 7 (ADR-0043), checked when a package is prepared: only format 7 has it; one file
 * or several up to ten; content types and a largest size; no default, no formula of its value, no formula reads it and
 * no table of lines has it. Each case changes the stored reference form in one place.
 */
class MdSimpleFormFilesTest {
    private static final Path CLAIMS = Path.of("src/test/resources/studio/golden/stored-declarations-v7.json");
    private static final Path ORDER = Path.of("src/test/resources/studio/golden/stored-declarations-v6.json");

    private final ObjectMapper json = JsonMapper.shared();
    private final MdSimpleFormDeclarations declarations = new MdSimpleFormDeclarations(json);

    @Test
    void theReferenceClaimIsAcceptedWithItsFileRules() throws Exception {
        var fields = declarations.prepare(bytes(claim())).declaration().fields();
        assertThat(fields.get(2).files()).isNull();
        assertThat(fields.get(3).files()).isEqualTo(new Files(false, null, List.of("image/png", "image/jpeg"), 5));
        assertThat(fields.get(4).files()).isEqualTo(new Files(true, 5, List.of("application/pdf"), null));
    }

    @Test
    void aFileFieldNeedsFormatSevenAndKeepsItsRulesInBounds() throws Exception {
        refused(root -> root.put("formatVersion", 6), "fields[2].type", "simple_form_unsupported");
        refused(root -> root.put("formatVersion", 8), "formatVersion", "simple_form_limit");
        refused(root -> field(root, 0).set("files", files(root, 3)), "fields[0].files", "simple_form_unsupported");
        refused(root -> field(root, 2).put("defaultValue", "x"), "fields[2].defaultValue", "simple_form_unsupported");
        refused(root -> field(root, 2).put("formula", "1"), "fields[2].formula", "simple_form_unsupported");
        refused(root -> rules(root, 4).put("maxFiles", 11), "fields[4].files.maxFiles", "simple_form_limit");
        refused(root -> rules(root, 4).put("maxFiles", 1), "fields[4].files.maxFiles", "simple_form_limit");
        refused(root -> rules(root, 4).remove("maxFiles"), "fields[4].files.maxFiles", "simple_form_missing");
        refused(root -> rules(root, 3).put("maxFiles", 3), "fields[3].files.maxFiles", "simple_form_unsupported");
        refused(root -> rules(root, 3).put("maxSizeMb", 51), "fields[3].files.maxSizeMb", "simple_form_limit");
        refused(root -> rules(root, 3).put("extra", 1), "fields[3].files", "simple_form_unknown_property");
        refused(root -> types(root, 3).add("image/png"), "fields[3].files.contentTypes[2]", "simple_form_duplicate");
        refused(
                root -> types(root, 3).set(0, json.getNodeFactory().stringNode("PNG")),
                "fields[3].files.contentTypes[0]",
                "simple_form_identifier");
        refused(root -> types(root, 3).removeAll(), "fields[3].files.contentTypes", "simple_form_limit");
        refused(
                root -> rules(root, 3).put("contentTypes", "image/png"),
                "fields[3].files.contentTypes",
                "simple_form_structure");
    }

    @Test
    void noFormulaReadsAFileFieldAndNoLineHoldsOne() throws Exception {
        refused(root -> field(root, 4).put("visibleIf", "act"), "fields[4].visibleIf", "formula_unknown_field");

        ObjectNode order = (ObjectNode) json.readTree(java.nio.file.Files.readString(ORDER))
                .get(0)
                .get("declaration")
                .deepCopy();
        order.put("formatVersion", 7);
        ((ObjectNode) order.get("lines").get(0).get("fields").get(1)).put("type", "file");
        assertThatThrownBy(() -> declarations.prepare(bytes(order)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.getFieldErrors())
                                .extracting(FieldErrorItem::field)
                                .contains("lines[0].fields[1].type"));
    }

    private void refused(Consumer<ObjectNode> change, String at, String code) throws Exception {
        ObjectNode root = claim();
        change.accept(root);
        assertThatThrownBy(() -> declarations.prepare(bytes(root)))
                .as("%s %s", at, code)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.getFieldErrors())
                                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                                .contains(org.assertj.core.groups.Tuple.tuple(at, code)));
    }

    private ObjectNode claim() throws Exception {
        JsonNode samples = json.readTree(java.nio.file.Files.readString(CLAIMS));
        return (ObjectNode) samples.get(0).get("declaration").deepCopy();
    }

    private byte[] bytes(JsonNode root) {
        return json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    private static ObjectNode field(ObjectNode root, int index) {
        return (ObjectNode) root.get("fields").get(index);
    }

    private static ObjectNode rules(ObjectNode root, int index) {
        return (ObjectNode) field(root, index).get("files");
    }

    private static JsonNode files(ObjectNode root, int index) {
        return rules(root, index).deepCopy();
    }

    private static ArrayNode types(ObjectNode root, int index) {
        return (ArrayNode) rules(root, index).get("contentTypes");
    }
}
