package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Create-only activation uses the ordinary entity API without another application start (ADR-0038). */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioActivationHttpTest extends EmbeddedPostgresTest {
    private static final String URL = "/api/v1/studio/forms";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MdSimpleFormDeclarations declarations;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        signIn(Set.of("*.*"));
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void activatesAndRepeatsTheSamePackageWithoutRestart() throws Exception {
        String pack = pack("live_http_catalog", false);
        String first = mvc.perform(
                        post(URL).contentType(MediaType.APPLICATION_JSON).content(pack))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/form-meta/studio.live_http_catalog"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.entityCode").value("studio.live_http_catalog"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(pack))
                        .andExpect(status().isOk())
                        .andExpect(header().doesNotExist("Location"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .isEqualTo(first);
        mvc.perform(get("/api/v1/form-meta/studio.live_http_catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("studio.live_http_catalog"));
        mvc.perform(post("/api/v1/entities/studio.live_http_catalog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TEST Record\",\"code\":\"test-record\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("TEST Record"));
    }

    @Test
    void changingAnActivatedDeclarationConflictsInsteadOfChangingItsSchema() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(pack("live_http_conflict", false)))
                .andExpect(status().isCreated());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(pack("live_http_conflict", true)))
                .andExpect(status().isConflict());
    }

    @Test
    void publishingHasItsOwnPermissionAndIsCheckedBeforeParsing() throws Exception {
        mvc.perform(get("/api/v1/studio/activation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        signIn(Set.of("md.settings.update"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/studio/activation")).andExpect(status().isForbidden());
        SecurityContext.clear();
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTamperingUnknownPropertiesAndOversizedPackages() throws Exception {
        ObjectNode packageNode = (ObjectNode) json.readTree(pack("live_http_invalid", false));
        packageNode.put("checksum", "0".repeat(64));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(packageNode)))
                .andExpect(status().isUnprocessableEntity());
        packageNode = (ObjectNode) json.readTree(pack("live_http_invalid", false));
        packageNode.put("sql", "select 1");
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(packageNode)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(65537)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rejectsSecretFieldIdentifiersAtTheActivationBoundary() throws Exception {
        ObjectNode packageNode = (ObjectNode) json.readTree(pack("live_http_secret", false));
        ObjectNode declaration =
                (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json")));
        ((ObjectNode) declaration.path("module")).put("code", "studio");
        ((ObjectNode) declaration.path("entity")).put("name", "live_http_secret");
        ((ObjectNode) declaration.path("fields").get(0)).put("id", "password");
        packageNode.set("declaration", declaration);
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(packageNode)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("fields[0].id"));
        mvc.perform(get("/api/v1/form-meta/studio.live_http_secret")).andExpect(status().isNotFound());
    }

    private String pack(String name, boolean changed) throws Exception {
        ObjectNode declaration =
                (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json")));
        ((ObjectNode) declaration.path("module")).put("code", "studio");
        ((ObjectNode) declaration.path("entity")).put("name", name);
        if (changed) ((ObjectNode) declaration.path("entity").path("title")).put("en", "Changed form");
        declaration.set("fields", json.createArrayNode());
        return json.writeValueAsString(declarations.prepare(json.writeValueAsBytes(declaration)));
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "studio-author", null, null, false, permissions, 1L, false, 1L, null));
    }
}
