package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.service.StudioFormsHealth;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A broken or quarantined live form never breaks declared entities or other live forms (ADR-0038). */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioQuarantineHttpTest extends EmbeddedPostgresTest {
    private static final String QUARANTINE = "/api/v1/studio/quarantine";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MdSimpleFormDeclarations declarations;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StudioFormsHealth health;

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
    void aQuarantinedFormAnswersAsUnknownUntilReleasedAndEveryChangeIsAudited() throws Exception {
        String code = activate("live_quarantine_http");
        mvc.perform(get("/api/v1/entities/" + code)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/history"))
                .andExpect(jsonPath("$[?(@ == '" + code + "')]").exists());
        mvc.perform(post(QUARANTINE).contentType(MediaType.APPLICATION_JSON).content(request(code, "Wrong fields")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", QUARANTINE + "/" + code))
                .andExpect(jsonPath("$.entityCode").value(code))
                .andExpect(jsonPath("$.reason").value("Wrong fields"));
        mvc.perform(get("/api/v1/entities/" + code)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/form-meta/" + code)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/history/" + code + "/1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/history"))
                .andExpect(jsonPath("$[?(@ == '" + code + "')]").doesNotExist());
        mvc.perform(post("/api/v1/studio/forms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack("live_quarantine_http")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("error.studio.quarantined"));
        mvc.perform(post(QUARANTINE).contentType(MediaType.APPLICATION_JSON).content(request(code, "Again")))
                .andExpect(status().isConflict());
        mvc.perform(get(QUARANTINE + "/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("Wrong fields"));
        mvc.perform(get("/api/v1/studio/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined[?(@.entityCode == '" + code + "')]")
                        .exists());
        mvc.perform(get("/api/v1/entities/ms.tasks")).andExpect(status().isOk());

        mvc.perform(delete(QUARANTINE + "/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/entities/" + code)).andExpect(status().isOk());
        mvc.perform(delete(QUARANTINE + "/" + code)).andExpect(status().isNotFound());
        mvc.perform(get(QUARANTINE + "/" + code)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select event from audit_log where table_name = 'studio_form_quarantine' "
                                + "and row_pk = (select id::text from studio_forms where code = :code) order by id")
                        .param("code", code)
                        .query(String.class)
                        .list())
                .containsExactly("I", "D");
    }

    @Test
    void unknownFormsInvalidRequestsAndOrdinaryAuthorsAreRefused() throws Exception {
        mvc.perform(post(QUARANTINE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("studio.never_activated", "Reason")))
                .andExpect(status().isNotFound());
        mvc.perform(post(QUARANTINE).contentType(MediaType.APPLICATION_JSON).content(request("md.users", "Reason")))
                .andExpect(status().isUnprocessableEntity());
        // A path value that is no live form code is answered as not quarantined, without being repeated.
        for (var probe : List.of(get(QUARANTINE + "/md.users"), delete(QUARANTINE + "/md.users"))) {
            mvc.perform(probe)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageKey").value("error.studio.not_quarantined"))
                    .andExpect(jsonPath("$.detail").value(not(containsString("md.users"))));
        }
        mvc.perform(post(QUARANTINE).contentType(MediaType.APPLICATION_JSON).content(request("studio.any", " ")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(QUARANTINE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("studio.any", "x".repeat(501))))
                .andExpect(status().isUnprocessableEntity());
        signIn(Set.of("md.settings.update"));
        mvc.perform(get("/api/v1/studio/catalog")).andExpect(status().isForbidden());
        mvc.perform(post(QUARANTINE).contentType(MediaType.APPLICATION_JSON).content(request("studio.any", "Reason")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aStoredFormThatNoLongerCompilesIsReportedWhileEverythingElseServes() throws Exception {
        String served = activate("live_survivor_http");
        String broken = "studio.live_broken_http";
        ObjectNode declaration = (ObjectNode) json.readTree(pack("live_broken_http"));
        ((ObjectNode) declaration.path("declaration").path("fields").get(0)).put("type", "money");
        jdbc.sql("insert into studio_forms (code, declaration, checksum, created_by) "
                        + "values (:code, cast(:declaration as jsonb), repeat('b', 64), 1)")
                .param("code", broken)
                .param("declaration", json.writeValueAsString(declaration.path("declaration")))
                .update();
        try {
            mvc.perform(get("/api/v1/entities/ms.tasks")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/form-meta/ms.tasks")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/entities/menu")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/entities/" + served)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/entities/" + broken)).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/studio/catalog"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.broken[?(@.entityCode == '" + broken + "')].reason")
                            .value("declaration"));
            var down = health.health();
            assertThat(down.getStatus()).isEqualTo(Status.DOWN);
            assertThat(down.getDetails()).containsKey("broken");
        } finally {
            // Stored declarations are immutable: quarantine is how an operator silences one for good.
            mvc.perform(post(QUARANTINE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request(broken, "Does not compile")))
                    .andExpect(status().isCreated());
        }
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(health.health().getDetails()).doesNotContainKey("broken");
        // The health count of held forms is the catalog view's, a held form that does not compile included.
        Integer listed = JsonPath.read(
                mvc.perform(get("/api/v1/studio/catalog"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.quarantined.length()");
        assertThat(health.health().getDetails()).containsEntry("quarantined", listed);
    }

    private String activate(String name) throws Exception {
        mvc.perform(post("/api/v1/studio/forms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack(name)))
                .andExpect(status().isCreated());
        return "studio." + name;
    }

    private String request(String code, String reason) {
        return json.writeValueAsString(Map.of("entityCode", code, "reason", reason));
    }

    private String pack(String name) throws Exception {
        ObjectNode declaration =
                (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json")));
        ((ObjectNode) declaration.path("module")).put("code", "studio");
        ((ObjectNode) declaration.path("entity")).put("name", name);
        return json.writeValueAsString(declarations.prepare(json.writeValueAsBytes(declaration)));
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "studio-operator", null, null, false, permissions, 1L, false, 1L, null));
    }
}
