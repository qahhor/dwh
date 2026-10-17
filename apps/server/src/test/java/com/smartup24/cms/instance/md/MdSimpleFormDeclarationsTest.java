package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MdSimpleFormDeclarationsTest {
    private final JsonMapper json = JsonMapper.shared();
    private final MdSimpleFormDeclarations declarations = new MdSimpleFormDeclarations(json);

    @Test
    void documentsGoldenFixtureAndCanonicalDigestAgree() throws Exception {
        byte[] fixture = fixture();
        assertThat(Files.readAllBytes(Path.of("../../docs/api/simple-form.example.json")))
                .isEqualTo(fixture);
        var first = declarations.prepare(fixture);
        assertThat(first.checksum())
                .isEqualTo(Files.readString(Path.of("src/test/resources/studio/simple-form.sha256"))
                        .trim());
        assertThat(declarations.prepare(json.writeValueAsBytes(json.readTree(fixture))))
                .isEqualTo(first);
    }

    @Test
    void acceptsZeroAndTwentyFieldsButNotTwentyOne() throws Exception {
        ObjectNode source = (ObjectNode) json.readTree(fixture());
        var fields = source.putArray("fields");
        assertThat(declarations
                        .prepare(json.writeValueAsBytes(source))
                        .declaration()
                        .fields())
                .isEmpty();
        for (int i = 0; i < 20; i++) {
            var field = fields.addObject()
                    .put("id", "extra" + i)
                    .put("type", "bool")
                    .put("required", false)
                    .put("listed", false);
            field.putObject("labels").put("ru", "Поле").put("uz", "Maydon").put("en", "Field");
        }
        assertThat(declarations
                        .prepare(json.writeValueAsBytes(source))
                        .declaration()
                        .fields())
                .hasSize(20);
        fields.add(fields.get(0));
        assertThatThrownBy(() -> declarations.prepare(json.writeValueAsBytes(source)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void formatTwoKeepsHiddenFieldsBeyondTwentyDefaultsAndAMenuItemAndDropsAFalseHiddenFlag() throws Exception {
        ObjectNode source = (ObjectNode) json.readTree(fixture());
        source.put("formatVersion", 2);
        ((ObjectNode) source.path("entity"))
                .putObject("menu")
                .put("icon", "event")
                .put("section", "administration")
                .put("order", 7);
        var fields = source.putArray("fields");
        for (int i = 0; i < 30; i++) {
            var field = fields.addObject()
                    .put("id", "extra" + i)
                    .put("type", "bool")
                    .put("required", false)
                    .put("listed", false)
                    .put("hidden", i >= 20)
                    .put("defaultValue", i % 2 == 0 ? "true" : "false");
            field.putObject("labels").put("ru", "Поле").put("uz", "Maydon").put("en", "Field");
        }
        var prepared = declarations.prepare(json.writeValueAsBytes(source)).declaration();
        assertThat(prepared.formatVersion()).isEqualTo(2);
        assertThat(prepared.entity().menu().icon()).isEqualTo("event");
        assertThat(prepared.fields()).hasSize(30);
        assertThat(prepared.fields().get(0).hidden()).isNull();
        assertThat(prepared.fields().get(29).hidden()).isTrue();
        assertThat(prepared.fields().get(1).defaultValue()).isEqualTo("false");
        assertThat(json.writeValueAsString(prepared.fields().get(0))).doesNotContain("hidden");
        ((ObjectNode) fields.get(20)).put("hidden", false);
        assertThatThrownBy(() -> declarations.prepare(json.writeValueAsBytes(source)))
                .isInstanceOf(ApiException.class);
        ((ObjectNode) fields.get(20)).put("hidden", true);
        fields.add(((ObjectNode) fields.get(29).deepCopy()).put("id", "extra30"));
        assertThatThrownBy(() -> declarations.prepare(json.writeValueAsBytes(source)))
                .isInstanceOf(ApiException.class);
    }

    /** Format 3 (ADR-0041): a select with its choices, references to a list and a live form, a user and the scope. */
    @Test
    void formatThreeKeepsChoicesTargetsUsersAndTheScope() throws Exception {
        ObjectNode source = formatThree();
        var prepared = declarations.prepare(json.writeValueAsBytes(source)).declaration();
        assertThat(prepared.formatVersion()).isEqualTo(3);
        assertThat(prepared.entity().scope()).isEqualTo("org_unit");
        assertThat(prepared.fields().get(0).options()).extracting("code").containsExactly("done", "later");
        assertThat(prepared.fields().get(0).defaultValue()).isEqualTo("done");
        assertThat(prepared.fields().get(1).target().kind()).isEqualTo("list");
        assertThat(prepared.fields().get(2).target().code()).isEqualTo("studio.clients");
        assertThat(prepared.fields().get(3).type()).isEqualTo("user");
        assertThat(json.writeValueAsString(prepared.fields().get(3)))
                .doesNotContain("options")
                .doesNotContain("target");

        source.put("formatVersion", 2);
        assertThatThrownBy(() -> declarations.prepare(json.writeValueAsBytes(source)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void formatThreeRefusesChoicesAndTargetsOutOfPlace() throws Exception {
        for (java.util.function.Consumer<ObjectNode> broken :
                java.util.List.<java.util.function.Consumer<ObjectNode>>of(
                        tree -> ((ObjectNode) tree.path("entity")).put("scope", "everyone"),
                        tree -> field(tree, 0).remove("options"),
                        tree -> field(tree, 0).putArray("options"),
                        tree -> field(tree, 0).put("defaultValue", "never"),
                        tree -> field(tree, 0)
                                .withArray("options")
                                .add(field(tree, 0).path("options").get(0)),
                        tree -> field(tree, 1).remove("target"),
                        tree -> field(tree, 1)
                                .putObject("target")
                                .put("kind", "table")
                                .put("code", "kinds"),
                        tree -> field(tree, 1)
                                .putObject("target")
                                .put("kind", "list")
                                .put("code", "Kinds"),
                        tree -> field(tree, 2)
                                .putObject("target")
                                .put("kind", "form")
                                .put("code", "md.users"),
                        tree -> field(tree, 2).put("defaultValue", "1"),
                        tree -> field(tree, 3).put("defaultValue", "1"),
                        tree -> field(tree, 3)
                                .putObject("target")
                                .put("kind", "list")
                                .put("code", "kinds"),
                        tree -> field(tree, 4).putArray("options"),
                        tree -> field(tree, 4).put("type", "users"))) {
            ObjectNode tree = formatThree();
            broken.accept(tree);
            assertThatThrownBy(() -> declarations.prepare(json.writeValueAsBytes(tree)))
                    .isInstanceOf(ApiException.class);
        }
    }

    private ObjectNode formatThree() throws Exception {
        ObjectNode source = (ObjectNode) json.readTree(fixture());
        source.put("formatVersion", 3);
        ((ObjectNode) source.path("entity")).put("scope", "org_unit");
        var fields = source.putArray("fields");
        var select = choice(fields.addObject(), "result", "select").put("defaultValue", "done");
        var options = select.putArray("options");
        labelled(options.addObject().put("code", "done"));
        labelled(options.addObject().put("code", "later"));
        choice(fields.addObject(), "kind", "ref")
                .putObject("target")
                .put("kind", "list")
                .put("code", "kinds");
        choice(fields.addObject(), "client", "ref")
                .putObject("target")
                .put("kind", "form")
                .put("code", "studio.clients");
        choice(fields.addObject(), "responsible", "user");
        choice(fields.addObject(), "note", "text").put("maxLength", 100);
        return source;
    }

    private static ObjectNode choice(ObjectNode field, String id, String type) {
        field.put("id", id).put("type", type).put("required", false).put("listed", true);
        labelled(field);
        return field;
    }

    private static ObjectNode labelled(ObjectNode node) {
        node.putObject("labels").put("ru", "Поле").put("uz", "Maydon").put("en", "Field");
        return node;
    }

    private static ObjectNode field(ObjectNode tree, int index) {
        return (ObjectNode) tree.path("fields").get(index);
    }

    @Test
    void limitsBytesExactlyAndCountsUnicodeLabelsAsCodePoints() throws Exception {
        byte[] source = fixture();
        String padded = new String(source, StandardCharsets.UTF_8) + " ".repeat(32768 - source.length);
        assertThat(declarations.prepare(padded.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(declarations.prepare(source));
        assertThatThrownBy(() -> declarations.prepare((padded + " ").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class);
        ObjectNode tree = (ObjectNode) json.readTree(source);
        ((ObjectNode) tree.path("entity").path("title")).put("en", "😀".repeat(80));
        assertThat(declarations
                        .prepare(json.writeValueAsBytes(tree))
                        .declaration()
                        .entity()
                        .title()
                        .en())
                .isEqualTo("😀".repeat(80));
        ((ObjectNode) tree.path("entity").path("title")).put("en", "😀".repeat(81));
        assertThatThrownBy(() -> declarations.prepare(json.writeValueAsBytes(tree)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsSystemFieldAndMissingOrWrongTypedMembers() throws Exception {
        String source = new String(fixture(), StandardCharsets.UTF_8);
        for (String invalid : new String[] {
            source.replace("\"address\"", "\"archived\""),
            source.replace("\"address\"", "\"modifiedat\""),
            source.replace("\"address\"", "null"),
            source.replace("\"kind\": \"simple-form\",", ""),
            source.replace("\"title\": {", "\"title\": ["),
            source.replace("\"maxLength\": 255", "\"maxLength\": 99999999999999999999")
        }) {
            assertThatThrownBy(() -> declarations.prepare(invalid.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void unicodeChecksumMatchesIndependentUtf8Golden() throws Exception {
        var body = Files.readAllBytes(Path.of("src/test/resources/studio/simple-form-unicode.json"));
        assertThat(declarations.prepare(body).checksum())
                .isEqualTo(Files.readString(Path.of("src/test/resources/studio/simple-form-unicode.sha256"))
                        .trim());
    }

    @Test
    void rejectsJavaPackageKeywordsSqlKeywordsAndPostgresSystemColumns() throws Exception {
        String source = new String(fixture(), StandardCharsets.UTF_8);
        for (String module :
                new String[] {"class", "for", "switch", "true", "con", "prn", "aux", "nul", "com1", "lpt1"}) {
            assertThatThrownBy(() -> declarations.prepare(
                            source.replace("\"catalog\"", "\"" + module + "\"").getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ApiException.class);
        }
        for (String field :
                new String[] {"select", "authorization", "from", "xmin", "tableoid", "ctid", "constructor"}) {
            assertThatThrownBy(() -> declarations.prepare(
                            source.replace("\"address\"", "\"" + field + "\"").getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ApiException.class);
        }
    }

    private static byte[] fixture() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json"));
    }
}
