package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutField;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutSection;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.example.service.ExampleProductsEntity;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class FormLayoutDeclarationsTest {
    static final String INITIAL = """
            {"formatVersion":1,"kind":"form-layout","entityCode":"example.products","sections":[
              {"id":"main","fields":[{"fieldId":"code"},{"fieldId":"name"},{"fieldId":"unit"},{"fieldId":"price"}]}]}
            """;
    static final String EDITED = """
            {"formatVersion":1,"kind":"form-layout","entityCode":"example.products","sections":[
              {"id":"pricing","labels":{"ru":"Цена","uz":"Narx","en":"Pricing"},
               "fields":[{"fieldId":"price","labels":{"ru":"Стоимость","uz":"Qiymat","en":"Cost"}},{"fieldId":"unit"}]},
              {"id":"main","fields":[{"fieldId":"name"},{"fieldId":"code"}]}]}
            """;
    private final FormLayoutDeclarations declarations = new FormLayoutDeclarations(JsonMapper.shared());
    private FormMeta meta;

    @BeforeEach
    void setUp() {
        signIn(Set.of("*.*"));
        meta = FormMetaController.of(ExampleProductsEntity.DEFINITION);
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    static void signIn(Set<String> rights) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                7L, "studio", "studio@example.test", 1L, false, rights, 1L, false, 1L, null));
    }

    @Test
    void editedDeclarationRoundTripsWithoutChangingTheEntityOrMetadata() {
        var before = ExampleProductsEntity.DEFINITION;
        var accepted = declarations.validate(bytes(EDITED), meta);
        assertThat(accepted.sections()).extracting(FormLayoutSection::id).containsExactly("pricing", "main");
        assertThat(accepted.sections().getFirst().fields())
                .extracting(FormLayoutField::fieldId)
                .containsExactly("price", "unit");
        assertThat(accepted.sections().getFirst().fields().getFirst().labels().en())
                .isEqualTo("Cost");
        var reloaded = declarations.validate(JsonMapper.shared().writeValueAsBytes(accepted), meta);
        assertThat(reloaded).isEqualTo(accepted);
        assertThat(meta).isEqualTo(FormMetaController.of(before));
        assertThat(ExampleProductsEntity.DEFINITION).isSameAs(before);
        assertThat(accepted.sections().stream()
                        .flatMap(section -> section.fields().stream())
                        .map(FormLayoutField::fieldId))
                .containsExactlyInAnyOrder("code", "name", "unit", "price");
    }

    @Test
    void initialDeclarationInheritsLabelsAndPreservesEveryVisibleField() {
        var initial = declarations.initial(meta);
        assertThat(declarations.validate(JsonMapper.shared().writeValueAsBytes(initial), meta))
                .isEqualTo(initial);
        assertThat(initial.sections()).allSatisfy(section -> {
            assertThat(section.labels()).isNull();
            assertThat(section.fields())
                    .allSatisfy(field -> assertThat(field.labels()).isNull());
        });
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void refusesUnsupportedOrMalformedDeclarationsWithoutChangingMetadata(String body) {
        assertThatThrownBy(() -> declarations.validate(bytes(body), meta))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getMessageKey()).isEqualTo("error.common.form_layout_invalid");
                    assertThat(error.getFieldErrors()).isNotEmpty();
                });
        assertThat(meta).isEqualTo(FormMetaController.of(ExampleProductsEntity.DEFINITION));
    }

    static Stream<String> invalidDeclarations() {
        return Stream.of(
                "null",
                "[]",
                "{}",
                INITIAL + "{}",
                INITIAL.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
                INITIAL.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
                INITIAL.replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
                INITIAL.replace("\"formatVersion\":1", "\"formatVersion\":2"),
                INITIAL.replace("form-layout", "sql"),
                INITIAL.replace("example.products", "md.users"),
                INITIAL.replace("\"kind\":", "\"$ref\":\"https://example.test/schema\",\"kind\":"),
                INITIAL.replace("\"kind\":", "\"$ref\":\"#\",\"kind\":"),
                INITIAL.replace("\"kind\":", "\"script\":\"throw Error()\",\"kind\":"),
                INITIAL.replace("\"id\":\"main\"", "\"id\":\"main\",\"type\":\"grid\""),
                INITIAL.replace("\"id\":\"main\"", "\"id\":\"Main\""),
                INITIAL.replace("\"id\":\"main\"", "\"id\":\"new\""),
                INITIAL.replace("{\"fieldId\":\"code\"}", "{\"fieldId\":\"code\",\"required\":false}"),
                INITIAL.replace("{\"fieldId\":\"code\"}", "{\"fieldId\":\"code\",\"readonly\":false}"),
                INITIAL.replace("{\"fieldId\":\"code\"}", "{\"fieldId\":\"code\",\"op\":\"eval\"}"),
                INITIAL.replace("\"fieldId\":\"code\"", "\"fieldId\":123"),
                INITIAL.replace("\"fieldId\":\"code\"", "\"fieldId\":\"modifiedAt\""),
                INITIAL.replace("\"fieldId\":\"code\"", "\"fieldId\":\"name\""),
                INITIAL.replace("{\"fieldId\":\"code\"},", ""),
                INITIAL.replace("\"fields\":[", "\"fields\":null,\"ignored\":["),
                EDITED.replace("\"uz\":\"Narx\",", ""),
                EDITED.replace("\"uz\":\"Narx\"", "\"uz\":null"),
                EDITED.replace("\"uz\":\"Narx\"", "\"uz\":\" \""),
                EDITED.replace("\"uz\":\"Narx\"", "\"uz\":\"Narx\",\"fr\":\"Prix\""),
                EDITED.replace("Pricing", "P".repeat(121)),
                EDITED.replace("Pricing", "Price\\nTotal"),
                INITIAL.replace("\"kind\":", "\"deep\":[[[[[[[[[]]]]]]]]],\"kind\":"));
    }

    @Test
    void refusesRawUtf8SizeAboveLimitAndInvalidEncoding() {
        byte[] oversized = bytes(" ".repeat(FormLayoutDeclarations.MAX_BODY_BYTES) + INITIAL);
        assertThatThrownBy(() -> declarations.validate(oversized, meta))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        assertThatThrownBy(() -> declarations.validate(new byte[] {(byte) 0xc3, (byte) 0x28}, meta))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void containerLimitsAndDepthHaveStructuredErrors() {
        String section = "{\"id\":\"main\",\"fields\":[{\"fieldId\":\"code\"}]}";
        String prefix =
                "{\"formatVersion\":1,\"kind\":\"form-layout\",\"entityCode\":\"example.products\",\"sections\":[";
        for (String body : java.util.List.of(
                prefix + String.join(",", java.util.Collections.nCopies(9, section)) + "]}",
                prefix + "{\"id\":\"main\",\"fields\":["
                        + String.join(",", java.util.Collections.nCopies(65, "{\"fieldId\":\"code\"}")) + "]}]}",
                prefix + "]}",
                "{\"deep\":[[[[[[[[[]]]]]]]]]}")) {
            assertThatThrownBy(() -> declarations.validate(bytes(body), meta))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            error -> assertThat(
                                            error.getFieldErrors().getFirst().messageKey())
                                    .isEqualTo("error.common.form_layout_limit"));
        }
    }

    @Test
    void unicodeLabelsCountCodePointsAndTheByteLimitIncludesWhitespace() {
        var accepted = declarations.validate(bytes(EDITED.replace("Pricing", "😀".repeat(120))), meta);
        assertThat(accepted.sections().getFirst().labels().en().codePointCount(0, 240))
                .isEqualTo(120);
        int padding = FormLayoutDeclarations.MAX_BODY_BYTES - bytes(INITIAL).length;
        assertThat(declarations.validate(bytes(INITIAL + " ".repeat(padding)), meta))
                .isEqualTo(declarations.initial(meta));
        assertThatThrownBy(() -> declarations.validate(bytes(EDITED.replace("Pricing", "\u00a0")), meta))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void duplicateSectionAndFieldKeysAreRefused() {
        assertThatThrownBy(() ->
                        declarations.validate(bytes(EDITED.replace("\"id\":\"pricing\"", "\"id\":\"main\"")), meta))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> declarations.validate(
                        bytes(INITIAL.replace("\"fieldId\":\"code\"", "\"fieldId\":\"code\",\"fieldId\":\"name\"")),
                        meta))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void documentedDeclarationIsAcceptedByTheServer() throws java.io.IOException {
        var body = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../docs/api/form-layout.example.json"));
        assertThat(declarations.validate(body, meta).entityCode()).isEqualTo("example.products");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
