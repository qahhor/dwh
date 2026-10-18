package com.smartup24.cms.instance.md;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class MdSimpleFormInvalidHttpTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "studio-author", null, null, false, Set.of("md.settings.update"), 1L, false, 1L, null));
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void refusesUnknownMalformedExecutableOrUnboundedDeclarations(byte[] body) throws Exception {
        mvc.perform(post("/api/v1/form-studio/new-forms/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    private static String format2(String declaration) {
        return declaration.replace("\"formatVersion\": 1", "\"formatVersion\": 2");
    }

    static Stream<byte[]> invalidBodies() throws Exception {
        String valid = Files.readString(Path.of("src/test/resources/studio/simple-form.json"));
        return Stream.concat(
                Stream.of(
                                "null",
                                "[]",
                                "{}",
                                valid + " {}",
                                valid.replace("\"formatVersion\": 1", "\"formatVersion\": 1, \"formatVersion\": 1"),
                                valid.replace("\"formatVersion\": 1", "\"formatVersion\": 1.0"),
                                valid.replace("\"formatVersion\": 1", "\"formatVersion\": 6"),
                                // Format 1 knows no hidden field, default or menu item (ADR-0041).
                                valid.replace("\"id\": \"active\",", "\"id\": \"active\", \"hidden\": true,"),
                                valid.replace("\"id\": \"active\",", "\"id\": \"active\", \"defaultValue\": \"true\","),
                                valid.replace("\"name\": \"locations\",", "\"name\": \"locations\", \"menu\": {},"),
                                format2(valid)
                                        .replace(
                                                "\"id\": \"active\",",
                                                "\"id\": \"active\", \"defaultValue\": \"yes\","),
                                format2(valid).replace("\"id\": \"active\",", "\"id\": \"active\", \"hidden\": 1,"),
                                format2(valid)
                                        .replace(
                                                "\"id\": \"address\",", "\"id\": \"address\", \"defaultValue\": \"\","),
                                format2(valid)
                                        .replace(
                                                "\"id\": \"address\",",
                                                "\"id\": \"address\", \"defaultValue\": \"a\\u0007b\","),
                                format2(valid)
                                        .replace(
                                                "\"name\": \"locations\",",
                                                "\"name\": \"locations\", "
                                                        + "\"menu\": {\"icon\": \"rocket\", \"section\": \"workspace\", \"order\": 1},"),
                                format2(valid)
                                        .replace(
                                                "\"name\": \"locations\",",
                                                "\"name\": \"locations\", "
                                                        + "\"menu\": {\"icon\": \"box\", \"section\": \"iam\", \"order\": 1},"),
                                format2(valid)
                                        .replace(
                                                "\"name\": \"locations\",",
                                                "\"name\": \"locations\", "
                                                        + "\"menu\": {\"icon\": \"box\", \"section\": \"workspace\", \"order\": 1000},"),
                                valid.replace("simple-form", "form-layout"),
                                valid.replace("\"kind\":", "\"sql\": \"drop table x\", \"kind\":"),
                                valid.replace("\"catalog\"", "\"../catalog\""),
                                valid.replace("\"locations\"", "\"x;drop\""),
                                valid.replace("\"address\"", "\"name\""),
                                valid.replace("\"address\"", "\"with_underscore\""),
                                valid.replace("\"details\"", "\"address\""),
                                valid.replace("\"text\"", "\"ref\""),
                                valid.replace("\"required\": true", "\"required\": 1"),
                                valid.replace("\"listed\": true", "\"listed\": null"),
                                valid.replace("\"maxLength\": 255", "\"maxLength\": 0"),
                                valid.replace("\"maxLength\": 255", "\"maxLength\": 4001"),
                                valid.replace("\"maxLength\": 255", "\"maxLength\": 1.5"),
                                valid.replace("\"maxLength\": 255", "\"scale\": 2"),
                                valid.replace("\"scale\": 2", "\"scale\": 7"),
                                valid.replace("\"scale\": 2", "\"scale\": -1"),
                                valid.replace("\"scale\": 2", "\"scale\": 2, \"maxLength\": 10"),
                                valid.replace("\"Locations\"", "\" \""),
                                valid.replace("\"Locations\"", "\" Locations\""),
                                valid.replace("\"Locations\"", "\"" + "x".repeat(81) + "\""),
                                valid.replace("\"Locations\"", "\"bad\\ntext\""),
                                valid.replace("\"Locations\"", "\"bad\\u202etext\""),
                                valid.replace("\"Locations\"", "\"bad\\u2028text\""),
                                valid.replace("\"Locations\"", "\"bad\\ud800text\""),
                                valid.replace("\"en\": \"Locations\"", "\"zz\": \"Locations\""),
                                "[".repeat(9) + "0" + "]".repeat(9))
                        .map(body -> body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Stream.of(new byte[] {(byte) 0xc3, 0x28}));
    }
}
