package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Format 4 of a simple form (ADR-0041): the title field names a required, listed, shown text field or the system
 * {@code name}; the layout puts each shown field on exactly one section and each section on one of 2 to 6 tabs when the
 * form has tabs. Each refusal points at the part to fix.
 */
class MdSimpleFormLayoutsTest {
    private final JsonMapper json = JsonMapper.shared();
    private final MdSimpleFormDeclarations declarations = new MdSimpleFormDeclarations(json);

    @Test
    void aLaidOutFormNamedByItsFieldReadsBack() {
        var declaration = declarations.prepare(bytes(valid())).declaration();
        assertThat(declaration.formatVersion()).isEqualTo(4);
        assertThat(declaration.entity().titleField()).isEqualTo("title");
        assertThat(declaration.layout().tabs()).extracting(tab -> tab.key()).containsExactly("main", "more");
        assertThat(declaration.layout().sections())
                .extracting(section -> section.tab())
                .containsExactly("main", "more");
    }

    @Test
    void theSystemNameAndNoTitleAreAcceptedAndALayoutIsOptional() {
        ObjectNode named = valid();
        ((ObjectNode) named.get("entity")).put("titleField", "name");
        named.remove("layout");
        assertThat(declarations.prepare(bytes(named)).declaration().layout()).isNull();
        ObjectNode numbered = valid();
        ((ObjectNode) numbered.get("entity")).remove("titleField");
        assertThat(declarations.prepare(bytes(numbered)).declaration().entity().titleField())
                .isNull();
    }

    @Test
    void anEarlierFormatKnowsNoLayoutOrTitle() {
        ObjectNode earlier = valid().put("formatVersion", 3);
        assertThat(problem(earlier)).isEqualTo("declaration:simple_form_unknown_property");
        ObjectNode untitled = valid().put("formatVersion", 3);
        untitled.remove("layout");
        assertThat(problem(untitled)).isEqualTo("entity:simple_form_unknown_property");
    }

    @ParameterizedTest
    @CsvSource({
        "phone, entity.titleField:simple_form_title",
        "hidden, entity.titleField:simple_form_title",
        "count, entity.titleField:simple_form_title",
        "missing, entity.titleField:simple_form_title"
    })
    void theTitleIsARequiredListedShownTextField(String field, String expected) {
        ObjectNode declaration = valid();
        ((ObjectNode) declaration.get("entity")).put("titleField", field);
        assertThat(problem(declaration)).isEqualTo(expected);
    }

    @Test
    void eachRefusedLayoutPointsAtItsPart() {
        assertThat(refused(layout -> layout.put("columns", 2))).isEqualTo("layout:simple_form_unknown_property");
        assertThat(refused(layout -> layout.put("sections", "x"))).isEqualTo("layout.sections:simple_form_structure");
        assertThat(refused(layout -> layout.putArray("sections"))).isEqualTo("layout.sections:simple_form_limit");
        assertThat(refused(layout -> sections(layout).get(1).put("key", "general")))
                .isEqualTo("layout.sections[1].key:simple_form_duplicate");
        assertThat(refused(layout -> sections(layout).get(0).put("key", "Bad")))
                .isEqualTo("layout.sections[0].key:simple_form_identifier");
        assertThat(refused(layout -> sections(layout).get(0).put("tab", "none")))
                .isEqualTo("layout.sections[0].tab:simple_form_layout");
        assertThat(refused(layout -> fields(layout, 1).add("title")))
                .isEqualTo("layout.sections[1].fields[1]:simple_form_duplicate");
        assertThat(refused(layout -> fields(layout, 1).add("hidden")))
                .isEqualTo("layout.sections[1].fields[1]:simple_form_layout");
        assertThat(refused(layout -> fields(layout, 1).add(7)))
                .isEqualTo("layout.sections[1].fields[1]:simple_form_structure");
        assertThat(refused(layout -> fields(layout, 1).removeAll()))
                .isEqualTo("layout.sections[1].fields:simple_form_limit");
        assertThat(refused(layout -> sections(layout).get(1).put("fields", "phone")))
                .isEqualTo("layout.sections[1].fields:simple_form_structure");
        assertThat(refused(layout -> fields(layout, 0).remove(1))).isEqualTo("layout.sections:simple_form_layout");
        assertThat(refused(layout -> ((ArrayNode) layout.get("tabs")).remove(1)))
                .isEqualTo("layout.tabs:simple_form_limit");
        assertThat(refused(layout -> layout.put("tabs", 1))).isEqualTo("layout.tabs:simple_form_structure");
        assertThat(refused(layout -> ((ObjectNode) layout.get("tabs").get(1)).put("key", "main")))
                .isEqualTo("layout.tabs[1].key:simple_form_duplicate");
        assertThat(refused(layout -> {
                    ((ArrayNode) layout.get("tabs"))
                            .addObject()
                            .put("key", "empty")
                            .set("labels", labels());
                }))
                .isEqualTo("layout.tabs[2]:simple_form_layout");
        assertThat(refused(layout -> {
                    layout.remove("tabs");
                }))
                .isEqualTo("layout.sections[0].tab:simple_form_unsupported");
    }

    private String refused(Consumer<ObjectNode> change) {
        ObjectNode declaration = valid();
        change.accept((ObjectNode) declaration.get("layout"));
        return problem(declaration);
    }

    private String problem(ObjectNode declaration) {
        try {
            declarations.prepare(bytes(declaration));
        } catch (ApiException refused) {
            FieldErrorItem item = refused.getFieldErrors().getFirst();
            return item.field() + ":" + item.code();
        }
        throw new AssertionError("The declaration was accepted");
    }

    private static ArrayNode fields(ObjectNode layout, int section) {
        return (ArrayNode) sections(layout).get(section).get("fields");
    }

    private static java.util.List<ObjectNode> sections(ObjectNode layout) {
        java.util.List<ObjectNode> result = new java.util.ArrayList<>();
        layout.get("sections").forEach(section -> result.add((ObjectNode) section));
        return result;
    }

    private byte[] bytes(ObjectNode declaration) {
        return json.writeValueAsBytes(declaration);
    }

    private ObjectNode labels() {
        return json.createObjectNode().put("ru", "Текст").put("uz", "Matn").put("en", "Text");
    }

    private ObjectNode valid() {
        ObjectNode root = json.createObjectNode().put("formatVersion", 4).put("kind", "simple-form");
        ObjectNode module = root.putObject("module").put("code", "studio");
        module.set("title", labels());
        ObjectNode entity = root.putObject("entity").put("name", "clients").put("titleField", "title");
        entity.set("title", labels());
        ArrayNode fields = root.putArray("fields");
        field(fields, "title", "text", true, true);
        field(fields, "phone", "text", false, true);
        field(fields, "count", "number", true, true);
        field(fields, "hidden", "text", true, true).put("hidden", true);
        ObjectNode layout = root.putObject("layout");
        ArrayNode tabs = layout.putArray("tabs");
        tabs.addObject().put("key", "main").set("labels", labels());
        tabs.addObject().put("key", "more").set("labels", labels());
        ArrayNode sections = layout.putArray("sections");
        ObjectNode general = sections.addObject().put("key", "general").put("tab", "main");
        general.set("labels", labels());
        general.putArray("fields").add("title").add("count");
        ObjectNode contacts = sections.addObject().put("key", "contacts").put("tab", "more");
        contacts.set("labels", labels());
        contacts.putArray("fields").add("phone");
        return root;
    }

    private ObjectNode field(ArrayNode fields, String id, String type, boolean required, boolean listed) {
        ObjectNode field = fields.addObject()
                .put("id", id)
                .put("type", type)
                .put("required", required)
                .put("listed", listed);
        if ("text".equals(type)) field.put("maxLength", 200);
        if ("number".equals(type)) field.put("scale", 0);
        field.set("labels", labels());
        return field;
    }
}
