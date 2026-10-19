package com.smartup24.cms.instance.studio.solution;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.service.StudioPackages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-0047: every shipped template is a package that verifies as an upload — its forms prepared by the server with
 * their checksums, its own checksum over its canonical JSON, its texts in ru, uz and en. With
 * {@code -Dtemplates.update=true} the test writes the checksums after a template was edited by hand.
 */
class SolutionTemplateFilesTest {
    static final Path TEMPLATES = Path.of("src/main/resources/studio/templates");

    private final ObjectMapper json = JsonMapper.shared();
    private final MdSimpleFormDeclarations declarations = new MdSimpleFormDeclarations(json);
    private final SolutionFormat format = new SolutionFormat(json, new StudioPackages(json, declarations));

    @Test
    void everyTemplateVerifiesLikeAnUpload() throws IOException {
        List<Path> files = files();
        assertThat(files).hasSizeGreaterThanOrEqualTo(3);
        for (Path file : files) {
            if (Boolean.getBoolean("templates.update")) update(file);
            SolutionPackage pack = format.read(Files.readAllBytes(file));
            assertThat(file.getFileName().toString()).isEqualTo(pack.code() + ".json");
            assertThat(pack.description()).as(pack.code()).isNotNull();
            assertThat(pack.forms()).as(pack.code()).isNotEmpty();
            assertThat(pack.reports()).as(pack.code()).isNotEmpty();
        }
        List<String> codes =
                files.stream().map(file -> file.getFileName().toString()).toList();
        assertThat(codes).contains("sales-visit.json", "client-request.json", "order-approval.json");
    }

    @Test
    void theOrderTemplateCarriesItsReportMailingAutomationAndRoles() throws IOException {
        SolutionPackage order = format.read(Files.readAllBytes(TEMPLATES.resolve("order-approval.json")));
        assertThat(order.reports()).extracting(report -> report.key()).containsExactly("orders-by-region");
        assertThat(order.mailings()).singleElement().satisfies(mailing -> {
            assertThat(mailing.report()).isEqualTo("orders-by-region");
            assertThat(mailing.recipients()).isEqualTo("mailing-recipients");
        });
        assertThat(order.automations())
                .singleElement()
                .satisfies(automation -> assertThat(automation.trigger().to()).containsExactly("pending"));
        assertThat(order.placeholders())
                .extracting(placeholder -> placeholder.key())
                .containsExactly("seller", "approver", "mailing-recipients");
    }

    /** Writes the checksums of the template's forms and of the template itself. */
    private void update(Path file) throws IOException {
        ObjectNode root = (ObjectNode) json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        for (JsonNode node : root.get("forms")) {
            var prepared = declarations.prepare(json.writeValueAsBytes(node.get("declaration")));
            ((ObjectNode) node).put("checksum", prepared.checksum());
        }
        root.put("checksum", format.checksum(root));
        Files.writeString(
                file, json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
    }

    private static List<Path> files() throws IOException {
        try (Stream<Path> found = Files.list(TEMPLATES)) {
            return found.filter(file -> file.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }
}
