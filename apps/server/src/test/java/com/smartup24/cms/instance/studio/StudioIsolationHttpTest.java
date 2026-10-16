package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
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

/** The live declaration must use the same isolated runtime for every exposed read path (ADR-0038). */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioIsolationHttpTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MdSimpleFormDeclarations declarations;

    @Autowired
    private EntityRegistry registry;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc;
    private String first;
    private String second;
    private long ownId;
    private long foreignId;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        signIn(Set.of("*.*"));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        first = activate("first_" + tag);
        second = activate("second_" + tag);
        ownId = create(first, "Own marker", 10);
        foreignId = create(second, "Foreign marker", 999);
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void foreignRecordCannotBeReadChangedArchivedDeletedOrOpenedInHistory() throws Exception {
        String foreign = "/api/v1/entities/" + first + "/" + foreignId;
        mvc.perform(get(foreign)).andExpect(status().isNotFound());
        mvc.perform(patch(foreign)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put(foreign + "/archived")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(foreign).header("If-Match", "\"1\"")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/history/" + first + "/" + foreignId)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/entities/" + first + "/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("action", "delete", "ids", java.util.List.of(foreignId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(0))
                .andExpect(jsonPath("$.failed").value(1));
        mvc.perform(get("/api/v1/history/" + first + "/" + ownId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].event").value("I"));
        mvc.perform(get("/api/v1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@ == '" + first + "')]").exists());
        mvc.perform(get("/api/v1/entities/" + second + "/" + foreignId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Foreign marker"))
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void listSearchFilterReportAndExporterStayInTheDeclaredPartition() throws Exception {
        String url = "/api/v1/entities/" + first;
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ownId));
        mvc.perform(get(url).param("q", "Foreign marker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(url).param(
                                "filter",
                                "[{\"field\":\"name\",\"op\":\"in\",\"value\":[\"Own marker\",\"Foreign marker\"]}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(url + "/report").param("measures", "[{\"op\":\"sum\",\"field\":\"capacity\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].values[0]").value(10));
        var exporter = registry.exporters().stream()
                .filter(item -> item.code().equals(first))
                .findFirst()
                .orElseThrow();
        assertThat(exporter.page(200, null, null, null, null, java.util.Map.of())
                        .items())
                .hasSize(1);
        mvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("list", first, "columns", java.util.List.of("name", "capacity")))))
                .andExpect(status().isAccepted());
        mvc.perform(get(url + "/" + ownId + "/files/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/search").param("q", "Own marker").param("entity", first))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokedReadPermissionClosesMetadataRecordsHistoryReportsAndExport() throws Exception {
        signIn(Set.of("md.profile.view", "studio.forms.publish", second + ".view"));
        for (String path : java.util.List.of(
                "/api/v1/form-meta/" + first,
                "/api/v1/entities/" + first,
                "/api/v1/entities/" + first + "/" + ownId,
                "/api/v1/entities/" + first + "/report",
                "/api/v1/history/" + first + "/" + ownId)) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("list", first))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/entities/" + second)).andExpect(status().isOk());
    }

    @Test
    void anExistingBrowserSessionLosesTheFormAndPrivateLabelsImmediatelyAfterRevocation() throws Exception {
        var users = com.smartup24.cms.instance.support.TestUsers.of(context);
        var viewer = users.withRights(java.util.Map.of(first, Set.of("view")), users.unit("studio-session"));
        SecurityContext.clear();
        var session = com.smartup24.cms.instance.support.TestSession.signIn(context, viewer.login());
        assertThat(session.send(get("/api/v1/form-meta/" + first)).getStatus()).isEqualTo(200);
        String label = "studio.live." + first.substring("studio.".length()) + ".title";
        var dictionary = session.send(get("/api/v1/i18n/en"));
        assertThat(dictionary.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(json.readTree(dictionary.getContentAsString()).has(label)).isTrue();
        var jdbc = context.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
        jdbc.sql(
                        "delete from md_role_permissions where form_code=:form and role_id in (select role_id from md_user_roles where user_id=:user)")
                .param("form", first)
                .param("user", viewer.id())
                .update();
        context.getBean(com.smartup24.cms.instance.md.service.MdPermissionService.class)
                .recalculateEffectivePermissions(viewer.id());
        assertThat(session.send(get("/api/v1/form-meta/" + first)).getStatus()).isEqualTo(404);
        assertThat(session.send(get("/api/v1/entities/" + first + "/" + ownId)).getStatus())
                .isEqualTo(404);
        assertThat(json.readTree(session.send(get("/api/v1/i18n/en")).getContentAsString())
                        .has(label))
                .isFalse();
        mvc.perform(get("/api/v1/i18n/en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + label + "']").doesNotExist());
    }

    @Test
    void switchingOffTheModuleClosesExistingFormsEvenForAnAdministrator() throws Exception {
        var modules = context.getBean(com.smartup24.cms.instance.md.service.ModuleRegistryService.class);
        modules.toggleModuleStatus("studio", false);
        try {
            for (String path : java.util.List.of(
                    "/api/v1/form-meta/" + first,
                    "/api/v1/entities/" + first,
                    "/api/v1/entities/" + first + "/report",
                    "/api/v1/history/" + first + "/" + ownId)) {
                mvc.perform(get(path)).andExpect(status().isNotFound());
            }
            mvc.perform(get("/api/v1/studio/activation")).andExpect(status().isNotFound());
            signIn(Set.of("md.profile.view", first + ".view"));
            String label = "studio.live." + first.substring("studio.".length()) + ".title";
            mvc.perform(get("/api/v1/i18n/en"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$['" + label + "']").doesNotExist());
            signIn(Set.of("*.*"));
        } finally {
            modules.toggleModuleStatus("studio", true);
        }
        mvc.perform(get("/api/v1/entities/" + first)).andExpect(status().isOk());
    }

    private String activate(String name) throws Exception {
        ObjectNode declaration =
                (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json")));
        ((ObjectNode) declaration.path("module")).put("code", "studio");
        ((ObjectNode) declaration.path("entity")).put("name", name);
        mvc.perform(post("/api/v1/studio/forms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(declarations.prepare(json.writeValueAsBytes(declaration)))))
                .andExpect(status().isCreated());
        return "studio." + name;
    }

    private long create(String code, String name, int capacity) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "name",
                name,
                "code",
                "shared-code",
                "address",
                "Test address",
                "capacity",
                capacity,
                "meeting",
                "2026-10-15T12:30:00+05:00",
                "active",
                true));
        String response = mvc.perform(post("/api/v1/entities/" + code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meeting").value("2026-10-15T07:30:00Z"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response).path("id").longValue();
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "studio-test", null, null, false, permissions, 1L, false, 1L, null));
    }
}
