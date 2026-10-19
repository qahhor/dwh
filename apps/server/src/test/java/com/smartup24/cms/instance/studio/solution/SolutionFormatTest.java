package com.smartup24.cms.instance.studio.solution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** ADR-0047: what reading a package trusts, in order — size, text, depth, checksum, format, structure, forms. */
class SolutionFormatTest {
    private final SolutionFixtures fixtures = new SolutionFixtures();
    private final SolutionFormat format = fixtures.format;

    @Test
    void theShippedOrderReadsAndSealsAgain() throws Exception {
        SolutionPackage pack = format.read(Files.readAllBytes(SolutionFixtures.ORDER));
        assertThat(pack.code()).isEqualTo("order-approval");
        assertThat(pack.forms()).hasSize(2);
        SolutionPackage again = format.read(format.seal(pack));
        assertThat(again.code()).isEqualTo("order-approval");
        assertThat(format.digest(pack.reports().getFirst()))
                .isEqualTo(format.digest(again.reports().getFirst()))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void sizeTextAndDepthAreBoundedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> SolutionFormat.requireSize(StudioPackageDtos.MAX_BYTES + 1L))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        assertThatThrownBy(() -> format.read(new byte[StudioPackageDtos.MAX_BYTES + 1]))
                .isInstanceOf(ApiException.class);
        invalid(new byte[] {(byte) 0xc3, (byte) 0x28});
        invalid("[".repeat(20) + "]".repeat(20));
        invalid("{\"a\": 1, \"a\": 2}");
        invalid("{} {}");
        invalid("[]");
        invalid("{\"checksum\": 1}");
        invalid("{\"checksum\": \"not-hex\"}");
    }

    @Test
    void aChangedFileIsNotTrusted() {
        ObjectNode root = fixtures.order();
        ((ObjectNode) root.get("title")).put("en", "Changed");
        assertThat(message(fixtures.json.writeValueAsBytes(root))).isEqualTo("error.studio.solution_checksum");
    }

    @Test
    void theFormatAndWhatThePackageRequiresAreChecked() {
        assertThat(message(fixtures.changed(root -> root.put("formatVersion", 2))))
                .isEqualTo("error.studio.solution_format");
        assertThat(message(fixtures.changed(root -> root.put("kind", "simple-form-package"))))
                .isEqualTo("error.studio.solution_format");
        ApiException newer =
                refused(fixtures.changed(root -> ((ObjectNode) root.get("requires")).put("simpleFormFormat", 8)));
        assertThat(newer.getMessageKey()).isEqualTo("error.studio.solution_newer");
        assertThat(newer.getParams()).containsEntry("format", 8).containsEntry("supported", 7);
    }

    @Test
    void theStructureAndEveryFormAreVerified() {
        assertThat(field(fixtures.changed(root -> root.put("extra", true)))).isEqualTo("extra");
        assertThat(field(fixtures.changed(root -> root.put("lists", "regions"))))
                .isEqualTo("package");
        assertThat(field(fixtures.changed(root -> ((ObjectNode) root.get("title")).put("note", "x"))))
                .isEqualTo("package");
        assertThat(field(fixtures.changed(root -> {
                    ArrayNode forms = (ArrayNode) root.get("forms");
                    for (int i = 0; i < 20; i++) forms.add(forms.get(0).deepCopy());
                })))
                .isEqualTo("forms");
        ObjectNode root = fixtures.order();
        ((ObjectNode) root.get("forms").get(0)).put("checksum", "0".repeat(64));
        root.put("checksum", format.checksum(root));
        ApiException form = refused(fixtures.json.writeValueAsBytes(root));
        assertThat(form.getFieldErrors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("forms[0]");
            assertThat(error.messageKey()).isEqualTo("error.studio.package_invalid");
        });
    }

    private void invalid(String text) {
        invalid(text.getBytes(StandardCharsets.UTF_8));
    }

    private void invalid(byte[] bytes) {
        assertThat(message(bytes)).isEqualTo("error.studio.solution_invalid");
    }

    private String message(byte[] bytes) {
        return refused(bytes).getMessageKey();
    }

    private String field(byte[] bytes) {
        ApiException refused = refused(bytes);
        assertThat(refused.getMessageKey()).isEqualTo("error.studio.solution_invalid");
        return refused.getFieldErrors().getFirst().field();
    }

    private ApiException refused(byte[] bytes) {
        try {
            format.read(bytes);
        } catch (ApiException refused) {
            return refused;
        }
        throw new AssertionError("The package was read");
    }
}
