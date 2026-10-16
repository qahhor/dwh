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
