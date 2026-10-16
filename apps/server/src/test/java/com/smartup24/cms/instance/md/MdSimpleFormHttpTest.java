package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class MdSimpleFormHttpTest extends EmbeddedPostgresTest {
    private static final String URL = "/api/v1/form-studio/new-forms/prepare";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        signIn(Set.of("md.settings.update"));
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void preparesRepeatablePackageWithoutCreatingAnEntityOrRequiringExamples() throws Exception {
        String first = mvc.perform(
                        post(URL).contentType(MediaType.APPLICATION_JSON).content(fixture()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.kind").value("simple-form-package"))
                .andExpect(jsonPath("$.checksum")
                        .value(Files.readString(Path.of("src/test/resources/studio/simple-form.sha256"))
                                .trim()))
                .andExpect(jsonPath("$.declaration.fields.length()").value(10))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(fixture()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .isEqualTo(first);
        signIn(Set.of("*.*"));
        mvc.perform(get("/api/v1/form-meta/catalog.locations")).andExpect(status().isNotFound());
    }

    @Test
    void authorityIsCheckedBeforeParsingAndAfterRevocation() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(fixture()))
                .andExpect(status().isOk());
        signIn(Set.of("md.settings.view"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        SecurityContext.clear();
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidDeclarationAnswersFieldProblemDetailsAndOversizeIsBounded() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.messageKey").value("error.md.simple_form_invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("formatVersion"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(32769)))
                .andExpect(status().isPayloadTooLarge());
    }

    private static byte[] fixture() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json"));
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "studio-author", null, null, false, permissions, 1L, false, 1L, null));
    }
}
