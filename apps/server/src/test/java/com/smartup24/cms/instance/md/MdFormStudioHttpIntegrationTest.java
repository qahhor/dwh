package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Transactional
class MdFormStudioHttpIntegrationTest extends EmbeddedPostgresTest {
    private static final String BASE = "/api/v1/form-studio/drafts/example.products";
    private static final String DECLARATION = """
            {"formatVersion":1,"kind":"form-layout","entityCode":"example.products","sections":[
              {"id":"main","fields":[{"fieldId":"code"},{"fieldId":"name"},{"fieldId":"unit"},{"fieldId":"price"}]}]}
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ModuleRegistryService modules;

    private MockMvc mvc;
    private long actor;

    @BeforeEach
    void setup() {
        actor = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        signIn(Set.of("*.*"));
        modules.toggleModuleStatus("example", true);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void clear() {
        modules.toggleModuleStatus("example", false);
        SecurityContext.clear();
    }

    @Test
    void absentDraftIsDistinctFromAnUnavailableEntityAndChangesRequireCas() throws Exception {
        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").isEmpty())
                .andExpect(jsonPath("$.meta.code").value("example.products"));
        create();
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(DECLARATION))
                .andExpect(status().isConflict());
        mvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content(DECLARATION))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(BASE)
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DECLARATION))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.draft.revision").value(2));
        mvc.perform(put(BASE)
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DECLARATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("revision_conflict"));
        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.revision").value(2));
    }

    @Test
    void prepareIsImmutableAndRepeatableWithoutChangingRuntimeOrDraft() throws Exception {
        String before = mvc.perform(get("/api/v1/form-meta/example.products"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        create();
        String first = seal(1);
        assertThat(seal(1)).isEqualTo(first);
        JsonNode version = json.readTree(first);
        mvc.perform(get(BASE + "/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksum").value(version.path("checksum").asString()));
        mvc.perform(post(BASE + "/versions/1/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(get(BASE)).andExpect(jsonPath("$.draft.revision").value(1));
        mvc.perform(put(BASE)
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DECLARATION))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + "/versions").header("If-Match", "1")).andExpect(status().isConflict());
        seal(2);
        String firstPage = mvc.perform(get(BASE + "/versions").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = json.readTree(firstPage).path("nextCursor").asString();
        mvc.perform(get(BASE + "/versions").param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
        assertThat(mvc.perform(get("/api/v1/form-meta/example.products"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .isEqualTo(before);
    }

    @Test
    void packageTamperingAndUnknownPropertiesAreRejected() throws Exception {
        create();
        String original = seal(1);
        ObjectNode changed = (ObjectNode) json.readTree(original);
        changed.put("checksum", "0".repeat(64));
        refusedPackage(json.writeValueAsString(changed));
        changed = (ObjectNode) json.readTree(original);
        ((ObjectNode) changed.path("package")).put("execute", "ignored");
        refusedPackage(json.writeValueAsString(changed));
        refusedPackage(original.replace("\"version\":1", "\"version\":\"1\""));
        refusedPackage(original.replaceFirst("\\{", "{\"checksum\":\"duplicate\","));
        refusedPackage(original + "{}");
    }

    @Test
    void authorAndEntityRightsAreCheckedAgainOnEveryReadAndMutation() throws Exception {
        create();
        String artifact = seal(1);
        signIn(Set.of("md.profile.view", "example.products.view"));
        mvc.perform(get(BASE)).andExpect(status().isForbidden());
        signIn(Set.of("md.settings.update"));
        for (String path : new String[] {BASE, BASE + "/versions", BASE + "/versions/1"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        mvc.perform(post(BASE + "/versions").header("If-Match", "1")).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/versions/1/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(artifact))
                .andExpect(status().isNotFound());
        signIn(Set.of("*.*"));
        modules.toggleModuleStatus("example", false);
        mvc.perform(get(BASE)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/versions")).andExpect(status().isNotFound());
    }

    @Test
    void limitsAndUnknownEntitiesFailBeforeWriting() throws Exception {
        mvc.perform(get(BASE + "/versions").param("limit", "101")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(BASE + "/versions").param("cursor", "invalid")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/form-studio/drafts/ms.notes")).andExpect(status().isNotFound());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(32769)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post(BASE + "/versions/1/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(" ".repeat(65537)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").isEmpty());
    }

    private void create() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(DECLARATION))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", BASE))
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.draft.revision").value(1));
    }

    private String seal(int revision) throws Exception {
        return mvc.perform(post(BASE + "/versions").header("If-Match", Integer.toString(revision)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void refusedPackage(String body) throws Exception {
        mvc.perform(post(BASE + "/versions/1/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    private void signIn(Set<String> rights) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                actor, "studio-test", "studio@example.test", null, false, rights, 1, false, 1, null));
    }
}
