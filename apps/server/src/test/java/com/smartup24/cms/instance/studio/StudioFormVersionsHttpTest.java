package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The versions and the lifecycle of a live form over HTTP (ADR-0041): a new version from the revision named, the
 * projection of records saved with an older version, a hidden field, the read-only archive and its interplay with the
 * quarantine.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioFormVersionsHttpTest extends EmbeddedPostgresTest {
    private static final String FORMS = "/api/v1/studio/forms";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MdSimpleFormDeclarations declarations;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityRegistry registry;

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
    void aNewVersionAddsFieldsThatOlderRecordsReadEmptyOrByTheirDefaultInEveryRead() throws Exception {
        String code = activate(base("versions_add"));
        long old = create(code, Map.of("name", "Old", "code", "old", "address", "Street", "capacity", 5));
        mvc.perform(get(FORMS + "/" + code))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.form.activeVersion").value(1))
                .andExpect(jsonPath("$.form.status").value("active"))
                .andExpect(jsonPath("$.form.records").value(1))
                .andExpect(jsonPath("$.versions.length()").value(1));

        ObjectNode next = base("versions_add");
        next.put("formatVersion", 2);
        ((ObjectNode) next.path("entity").path("title")).put("ru", "Локации 2");
        ((ObjectNode) next.path("fields").get(0)).put("maxLength", 200);
        fields(next).add(field("region", "text", false, true).put("maxLength", 50));
        fields(next).add(field("priority", "number", true, true).put("scale", 0).put("defaultValue", "3"));
        mvc.perform(version(code, null, next)).andExpect(status().isPreconditionRequired());
        mvc.perform(version(code, "\"7\"", next)).andExpect(status().isConflict());
        mvc.perform(version(code, "\"1\"", next))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", FORMS + "/" + code))
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.form.activeVersion").value(2))
                .andExpect(jsonPath("$.form.versions").value(2))
                .andExpect(jsonPath("$.form.title.ru").value("Локации 2"))
                .andExpect(jsonPath("$.versions[1].version").value(2));
        mvc.perform(version(code, "\"2\"", next))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.messageKey").value("error.studio.version_unchanged"));

        JsonNode before = read(code, old);
        assertThat(before.path("region").isNull() || before.path("region").isMissingNode())
                .isTrue();
        assertThat(before.path("priority").decimalValue()).isEqualByComparingTo("3");
        long fresh = create(
                code,
                Map.of(
                        "name",
                        "New",
                        "code",
                        "new",
                        "address",
                        "Avenue",
                        "capacity",
                        1,
                        "region",
                        "North",
                        "priority",
                        7));
        long defaulted = create(code, Map.of("name", "Def", "code", "def", "address", "Lane", "capacity", 2));
        assertThat(read(code, defaulted).path("priority").decimalValue()).isEqualByComparingTo("3");
        assertThat(payloadHas(old, "priority")).isFalse();
        assertThat(payloadHas(defaulted, "priority")).isTrue();
        assertThat(versionOf(old)).isEqualTo(1);
        assertThat(versionOf(fresh)).isEqualTo(2);

        String url = "/api/v1/entities/" + code;
        mvc.perform(get(url).param("filter", "[{\"field\":\"region\",\"op\":\"eq\",\"value\":\"North\"}]"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(fresh));
        mvc.perform(get(url).param("filter", "[{\"field\":\"priority\",\"op\":\"eq\",\"value\":3}]"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get(url + "/report").param("measures", "[{\"op\":\"sum\",\"field\":\"priority\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].values[0]").value(13));
        var exporter = registry.exporters().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElseThrow();
        assertThat(exporter.page(200, null, null, null, null, Map.of()).items()).hasSize(3);

        // A write always takes the active version; the projected default keeps the older record valid.
        mvc.perform(patch(url + "/" + old)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"Street 2\"}"))
                .andExpect(status().isOk());
        assertThat(versionOf(old)).isEqualTo(2);
        assertThat(jdbc.sql("select event from audit_log where table_name in ('studio_form_versions',"
                                + " 'studio_form_state') and new_row->>'code' = :code order by id")
                        .param("code", code)
                        .query(String.class)
                        .list())
                .containsExactly("I", "U");
        assertThat(jdbc.sql("select name from md_forms where code = :code")
                        .param("code", code)
                        .query(String.class)
                        .single())
                .isEqualTo("Локации 2");
        assertThat(jdbc.sql("select count(*) from md_form_actions where form_code = :code")
                        .param("code", code)
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void incompatibleChangesAreRefusedEachByFieldAndChangeAndNothingIsStored() throws Exception {
        String code = activate(base("versions_refused"));
        ObjectNode next = base("versions_refused");
        ((ObjectNode) next.path("fields").get(2)).put("type", "text").remove("scale");
        ((ObjectNode) next.path("fields").get(2)).put("maxLength", 10);
        ((ObjectNode) next.path("fields").get(0)).put("maxLength", 10);
        mvc.perform(version(code, "\"1\"", next))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.messageKey").value("error.studio.change_incompatible"))
                .andExpect(jsonPath("$.errors[*].code").value(hasItem("change_type")))
                .andExpect(jsonPath("$.errors[*].code").value(hasItem("change_max_length")));
        ObjectNode other = base("versions_other");
        mvc.perform(version(code, "\"1\"", other))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.messageKey").value("error.studio.code_mismatch"));
        mvc.perform(get(FORMS + "/" + code))
                .andExpect(jsonPath("$.form.versions").value(1));
        mvc.perform(get(FORMS + "/studio.never_there")).andExpect(status().isNotFound());
        mvc.perform(get(FORMS + "/md.users")).andExpect(status().isNotFound());
        signIn(Set.of("md.settings.update"));
        mvc.perform(get(FORMS)).andExpect(status().isForbidden());
        mvc.perform(post(FORMS + "/" + code + "/archive").header("If-Match", "\"1\""))
                .andExpect(status().isForbidden());
    }

    @Test
    void aHiddenFieldKeepsItsDataButLeavesTheFormTheListTheExportAndTheHistory() throws Exception {
        String code = activate(base("versions_hidden"));
        long id = create(code, Map.of("name", "Kept", "code", "kept", "address", "Street", "details", "Secret note"));
        ObjectNode hidden = base("versions_hidden");
        hidden.put("formatVersion", 2);
        ((ObjectNode) hidden.path("fields").get(1)).put("hidden", true);
        mvc.perform(version(code, "\"1\"", hidden)).andExpect(status().isCreated());

        assertThat(read(code, id).has("details")).isFalse();
        mvc.perform(get("/api/v1/form-meta/" + code))
                .andExpect(jsonPath("$.fields[*].key").value(not(hasItem("details"))));
        mvc.perform(get("/api/v1/history/" + code + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].changes[*].field").value(not(hasItem("details"))))
                .andExpect(jsonPath("$.items[*].changes[*].field").value(hasItem("address")));
        var exporter = registry.exporters().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElseThrow();
        assertThat(json.writeValueAsString(exporter.page(200, null, null, null, null, Map.of())))
                .doesNotContain("Secret note");
        mvc.perform(patch("/api/v1/entities/" + code + "/" + id)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"Street 2\"}"))
                .andExpect(status().isOk());
        assertThat(payload(id).path("details").asString()).isEqualTo("Secret note");

        ObjectNode shown = base("versions_hidden");
        shown.put("formatVersion", 2);
        mvc.perform(version(code, "\"2\"", shown)).andExpect(status().isCreated());
        assertThat(read(code, id).path("details").asString()).isEqualTo("Secret note");
    }

    @Test
    void anArchivedFormIsReadOnlyLeavesTheMenuAndReturnsByRestore() throws Exception {
        String code = activate(base("versions_archive"));
        long id = create(code, Map.of("name", "Rec", "code", "rec", "address", "Street"));
        String url = "/api/v1/entities/" + code;
        mvc.perform(post(FORMS + "/" + code + "/archive")).andExpect(status().isPreconditionRequired());
        mvc.perform(post(FORMS + "/" + code + "/archive").header("If-Match", "\"3\""))
                .andExpect(status().isConflict());
        mvc.perform(post(FORMS + "/" + code + "/archive").header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("archived"));

        mvc.perform(get("/api/v1/entities/menu"))
                .andExpect(jsonPath("$[*].code").value(not(hasItem(code))));
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(url + "/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/history/" + code + "/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/form-meta/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions.length()").value(0));
        mvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("list", code, "columns", List.of("name")))))
                .andExpect(status().isAccepted());
        expectArchived(mvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "X", "code", "x", "address", "Y")))));
        expectArchived(mvc.perform(patch(url + "/" + id)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"Z\"}")));
        expectArchived(mvc.perform(delete(url + "/" + id).header("If-Match", "\"1\"")));
        expectArchived(mvc.perform(put(url + "/" + id + "/archived")
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"archived\":true}")));
        expectArchived(mvc.perform(post(url + "/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("action", "delete", "ids", List.of(id))))));
        expectArchived(
                mvc.perform(version(code, "\"2\"", base("versions_archive").put("formatVersion", 2))));
        expectArchived(mvc.perform(post(FORMS + "/" + code + "/archive").header("If-Match", "\"2\"")));
        mvc.perform(get(FORMS).param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.entityCode == '" + code + "')].status")
                        .value("archived"));
        mvc.perform(get(FORMS).param("limit", "101")).andExpect(status().isUnprocessableEntity());

        mvc.perform(post(FORMS + "/" + code + "/restore").header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.revision").value(3));
        mvc.perform(post(FORMS + "/" + code + "/restore").header("If-Match", "\"3\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("error.studio.not_archived"));
        create(code, Map.of("name", "Again", "code", "again", "address", "Street"));
        mvc.perform(get("/api/v1/entities/menu"))
                .andExpect(jsonPath("$[*].code").value(hasItem(code)));
        assertThat(jdbc.sql("select new_row->>'status' from audit_log where table_name = 'studio_form_state'"
                                + " and new_row->>'code' = :code order by id")
                        .param("code", code)
                        .query(String.class)
                        .list())
                .containsExactly("archived", "active");
    }

    @Test
    void theQuarantineWinsOverTheArchiveAndAQuarantinedFormMayTakeANewVersion() throws Exception {
        String code = activate(base("versions_held"));
        mvc.perform(post("/api/v1/studio/quarantine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("entityCode", code, "reason", "Check"))))
                .andExpect(status().isCreated());
        ObjectNode next = base("versions_held");
        next.put("formatVersion", 2);
        fields(next).add(field("extra", "bool", false, true));
        mvc.perform(version(code, "\"1\"", next)).andExpect(status().isCreated());
        mvc.perform(post(FORMS + "/" + code + "/archive").header("If-Match", "\"2\""))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/entities/" + code)).andExpect(status().isNotFound());
        mvc.perform(get(FORMS + "/" + code))
                .andExpect(jsonPath("$.form.quarantined").value(true))
                .andExpect(jsonPath("$.form.status").value("archived"));
        mvc.perform(delete("/api/v1/studio/quarantine/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/entities/" + code)).andExpect(status().isOk());
        expectArchived(mvc.perform(post("/api/v1/entities/" + code)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "X", "code", "x", "address", "Y")))));
    }

    private void expectArchived(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("error.studio.archived"));
    }

    private ObjectNode base(String name) throws Exception {
        ObjectNode declaration = json.createObjectNode();
        declaration.put("formatVersion", 1).put("kind", "simple-form");
        declaration.putObject("module").put("code", "studio").set("title", labels("Созданные формы"));
        declaration.putObject("entity").put("name", name).set("title", labels("Локации"));
        ArrayNode fields = declaration.putArray("fields");
        fields.add(field("address", "text", true, true).put("maxLength", 100));
        fields.add(field("details", "textarea", false, false).put("maxLength", 500));
        fields.add(field("capacity", "number", false, true).put("scale", 1));
        return declaration;
    }

    private ObjectNode field(String id, String type, boolean required, boolean listed) {
        ObjectNode field = json.createObjectNode();
        field.put("id", id).put("type", type).set("labels", labels("Поле " + id));
        return field.put("required", required).put("listed", listed);
    }

    private ObjectNode labels(String text) {
        return json.createObjectNode().put("ru", text).put("uz", text).put("en", text);
    }

    private static ArrayNode fields(ObjectNode declaration) {
        return (ArrayNode) declaration.path("fields");
    }

    private String activate(ObjectNode declaration) throws Exception {
        mvc.perform(post(FORMS).contentType(MediaType.APPLICATION_JSON).content(pack(declaration)))
                .andExpect(status().isCreated());
        return "studio." + declaration.path("entity").path("name").asString();
    }

    private org.springframework.test.web.servlet.RequestBuilder version(
            String code, String ifMatch, ObjectNode declaration) throws Exception {
        var request = post(FORMS + "/" + code + "/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(pack(declaration));
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private byte[] pack(ObjectNode declaration) throws Exception {
        return json.writeValueAsBytes(declarations.prepare(json.writeValueAsBytes(declaration)));
    }

    private long create(String code, Map<String, ?> body) throws Exception {
        String response = mvc.perform(post("/api/v1/entities/" + code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response).path("id").longValue();
    }

    private JsonNode read(String code, long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/entities/" + code + "/" + id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private JsonNode payload(long id) {
        return json.readTree(jdbc.sql("select payload::text from studio_records where id = :id")
                .param("id", id)
                .query(String.class)
                .single());
    }

    private boolean payloadHas(long id, String key) {
        return payload(id).has(key);
    }

    private int versionOf(long id) {
        return jdbc.sql("select form_version from studio_records where id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "studio-versions", null, null, false, permissions, 1L, false, 1L, null));
    }
}
