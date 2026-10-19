package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.solution.SolutionFormat;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-0047 over the server alone: the template of an order with lines and approval is checked without changing
 * anything, refused without its required mapping, applied with the approver and seller roles and the recipients of
 * its mailing mapped — its automation and mailing paused — and applied again without creating anything; a solution
 * exported from this installation travels into it again under new codes; a changed file, an incompatible new version
 * of a form and a value naming a record are refused with their reasons; every template imports cleanly; a person
 * without the right gets 403.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioPackageHttpTest extends EmbeddedPostgresTest {
    private static final String API = "/api/v1/studio/packages";
    private static final Path ORDER = Path.of("src/main/resources/studio/templates/order-approval.json");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdPermissionService permissions;

    @Autowired
    private MdRoleRepository roles;

    @Autowired
    private SolutionFormat format;

    @Autowired
    private MdSimpleFormDeclarations declarations;

    @Autowired
    private ObjectMapper json;

    private final List<String> created = new ArrayList<>();
    private TestUsers users;
    private TestSession importer;
    private TestSession outsider;
    private String suffix;
    private long seller;
    private long approver;
    private TestUser first;
    private TestUser second;

    @BeforeEach
    void setup() throws Exception {
        users = TestUsers.of(context);
        String tag = UUID.randomUUID().toString().replace("-", "");
        suffix = "_" + tag.substring(0, 5);
        long unit = users.unit("pkg-" + tag.substring(0, 8));
        TestUser admin = users.withRights(Map.of("md.users", Set.of("view")), unit);
        jdbc.sql("insert into md_user_roles (user_id, role_id) select :user, id from md_roles where pcode = 'admin'")
                .param("user", admin.id())
                .update();
        permissions.recalculateEffectivePermissions(admin.id());
        importer = TestSession.signIn(context, admin.login());
        outsider = TestSession.signIn(
                context,
                users.withRights(Map.of("studio.forms", Set.of("publish")), unit)
                        .login());
        seller = roles.create("TEST seller " + tag, null, "A", 900).id();
        approver = roles.create("TEST approver " + tag, null, "A", 900).id();
        first = users.withRights(Map.of(), unit);
        second = users.withRights(Map.of(), unit);
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void archiveForms() throws Exception {
        for (String code : created) {
            MockHttpServletResponse read = importer.send(get("/api/v1/studio/forms/" + code));
            if (read.getStatus() != 200) continue;
            Map<String, Object> form =
                    (Map<String, Object>) TestSession.object(read).get("form");
            if (!"active".equals(form.get("status"))) continue;
            MockHttpServletResponse archived = importer.send(
                    post("/api/v1/studio/forms/" + code + "/archive")
                            .header("If-Match", "\"" + form.get("revision") + "\""),
                    Map.of(),
                    null);
            assertThat(archived.getStatus()).as(archived.getContentAsString()).isEqualTo(200);
        }
    }

    @Test
    void theOrderTemplateIsCheckedAppliedPausedAndAgainUnchanged() throws Exception {
        assertThat(TestSession.array(importer.send(get("/api/v1/studio/templates"))))
                .extracting(item -> String.valueOf(((Map<?, ?>) item).get("code")))
                .contains("order-approval", "sales-visit", "client-request");
        Map<String, Object> preview = fromTemplate("order-approval", suffix);
        String orders = "studio.orders" + suffix;
        assertThat(outcomes(preview))
                .containsEntry("list:regions" + suffix, "create")
                .containsEntry("list:products" + suffix, "create")
                .containsEntry("form:studio.clients" + suffix, "create")
                .containsEntry("form:" + orders, "create")
                .containsEntry("grant:approver", "skip")
                .containsEntry("report:orders-by-region", "create")
                .containsEntry("mailing:orders-by-region-weekly", "create")
                .containsEntry("automation:order-approval", "create");
        assertThat(importer.send(get("/api/v1/studio/forms/" + orders)).getStatus())
                .as("a check changes nothing")
                .isEqualTo(404);
        long id = number(preview.get("id"));

        MockHttpServletResponse unmapped = apply(id, Map.of());
        assertThat(unmapped.getStatus()).isEqualTo(422);
        assertThat(unmapped.getContentAsString()).contains("error.studio.solution_mapping_invalid");

        Map<String, Object> applied = applied(id, mapping());
        assertThat(applied.get("status")).isEqualTo("applied");
        assertThat(importer.send(get("/api/v1/studio/forms/" + orders)).getStatus())
                .isEqualTo(200);
        assertThat(jdbc.sql("select active from studio_automations where form_code = :form")
                        .param("form", orders)
                        .query(Boolean.class)
                        .list())
                .containsExactly(false);
        assertThat(jdbc.sql("select active from report_mailings where entity_code = :form")
                        .param("form", orders)
                        .query(Boolean.class)
                        .list())
                .containsExactly(false);
        assertThat(rights(approver)).contains(orders + ".approve", orders + ".reject");
        assertThat(rights(seller)).contains(orders + ".submit", orders + ".create");
        assertThat(apply(id, mapping()).getStatus()).isEqualTo(409);

        Map<String, Object> again = fromTemplate("order-approval", suffix);
        assertThat(outcomes(again))
                .containsEntry("form:" + orders, "unchanged")
                .containsEntry("list:products" + suffix, "unchanged")
                .containsEntry("report:orders-by-region", "unchanged")
                .containsEntry("mailing:orders-by-region-weekly", "unchanged")
                .containsEntry("automation:order-approval", "unchanged");
        Map<String, Object> repeated = applied(number(again.get("id")), mapping());
        assertThat(outcomes(repeated)).containsEntry("grant:approver", "unchanged");
        assertThat(jdbc.sql("select count(*) from studio_automations where form_code = :form")
                        .param("form", orders)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void anExportedSolutionTravelsUnderNewCodes() throws Exception {
        String orders = "studio.orders" + suffix;
        applied(number(fromTemplate("order-approval", suffix).get("id")), mapping());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("code", "orders-copy");
        request.put("version", "1.2.0");
        request.put("title", Map.of("ru", "Копия заказов", "uz", "Buyurtmalar nusxasi", "en", "Copy of orders"));
        request.put("forms", List.of(orders));
        request.put("reports", List.of(object("report")));
        request.put("mailings", List.of(object("mailing")));
        request.put("automations", List.of(object("automation")));

        MockHttpServletResponse planned = importer.send(post(API + "/export/preview"), request, null);
        assertThat(planned.getStatus()).as(planned.getContentAsString()).isEqualTo(200);
        String plan = planned.getContentAsString(StandardCharsets.UTF_8);
        assertThat(plan).contains("studio.clients" + suffix, "products" + suffix, "\"dependency\"");

        MockHttpServletResponse exported = importer.send(post(API + "/export"), request, null);
        assertThat(exported.getStatus()).as(exported.getContentAsString()).isEqualTo(200);
        assertThat(exported.getHeader("Content-Disposition")).contains("orders-copy-1.2.0.json");
        JsonNode file = json.readTree(exported.getContentAsByteArray());
        assertThat(file.path("mailings").path(0).path("recipients").asString()).startsWith("mailing-");
        assertThat(file.path("placeholders").toString()).doesNotContain(first.login());
        assertThat(file.path("forms")).hasSize(2);

        String next = "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        Map<String, Object> preview = upload(exported.getContentAsByteArray(), next);
        assertThat(outcomes(preview))
                .containsEntry("form:" + orders + next, "create")
                .containsEntry("form:studio.clients" + suffix + next, "create");
        Map<String, Object> mapping = new LinkedHashMap<>();
        for (Object placeholder : (List<?>) preview.get("placeholders")) {
            Map<?, ?> item = (Map<?, ?>) placeholder;
            if ("users".equals(item.get("kind"))) mapping.put(String.valueOf(item.get("key")), List.of(first.id()));
        }
        Map<String, Object> applied = applied(number(preview.get("id")), mapping);
        assertThat(applied.get("status")).isEqualTo("applied");
        assertThat(importer.send(get("/api/v1/studio/forms/" + orders + next)).getStatus())
                .isEqualTo(200);
    }

    @Test
    void aChangedFileIsRefused() throws Exception {
        String text = Files.readString(ORDER, StandardCharsets.UTF_8).replace("Чай", "Чай с сахаром");
        MockHttpServletResponse tampered =
                importer.send(post(API + "/imports").param("suffix", suffix), json.readTree(text), null);
        assertThat(tampered.getStatus()).isEqualTo(422);
        assertThat(tampered.getContentAsString()).contains("error.studio.solution_checksum");
    }

    @Test
    void anIncompatibleVersionOfAFormIsRefusedWithItsReasons() throws Exception {
        applied(number(fromTemplate("order-approval", suffix).get("id")), mapping());
        ObjectNode root = (ObjectNode) json.readTree(Files.readString(ORDER, StandardCharsets.UTF_8));
        ObjectNode form = (ObjectNode) root.path("forms").path(1);
        for (JsonNode field : form.path("declaration").path("fields")) {
            if ("note".equals(field.path("id").asString())) ((ObjectNode) field).put("type", "text");
        }
        form.put(
                "checksum",
                declarations
                        .prepare(json.writeValueAsBytes(form.get("declaration")))
                        .checksum());
        root.put("checksum", format.checksum(root));

        Map<String, Object> preview = upload(json.writeValueAsBytes(root), suffix);
        assertThat(outcomes(preview))
                .containsEntry("form:studio.orders" + suffix, "refused")
                .containsEntry("form:studio.clients" + suffix, "unchanged");
        assertThat(preview.toString()).contains("error.studio.change_incompatible");
        MockHttpServletResponse refused = apply(number(preview.get("id")), mapping());
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("error.studio.solution_refused");
    }

    @Test
    void aValueThatNamesARecordIsNotExported() throws Exception {
        String orders = "studio.orders" + suffix;
        applied(number(fromTemplate("order-approval", suffix).get("id")), mapping());
        Map<String, Object> state = Map.of(
                "groupBy", List.of(Map.of("field", "region")),
                "measures", List.of(Map.of("op", "count")),
                "filter", List.of(Map.of("field", "client", "op", "eq", "value", 1)),
                "chart", "table");
        MockHttpServletResponse report = importer.send(
                post("/api/v1/list-views/" + orders),
                Map.of("name", "По клиенту", "state", state, "isDefault", false, "kind", "report"),
                null);
        assertThat(report.getStatus()).as(report.getContentAsString()).isEqualTo(201);
        Map<String, Object> request = Map.of(
                "code",
                "named-record",
                "version",
                "1.0.0",
                "title",
                Map.of("ru", "Пакет", "uz", "Paket", "en", "Package"),
                "reports",
                List.of(TestSession.object(report).get("id")));
        MockHttpServletResponse refused = importer.send(post(API + "/export"), request, null);
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("error.studio.solution_record_value");
    }

    @Test
    void everyTemplateImportsCleanly() throws Exception {
        for (Object item : TestSession.array(importer.send(get("/api/v1/studio/templates")))) {
            String code = String.valueOf(((Map<?, ?>) item).get("code"));
            Map<String, Object> preview = fromTemplate(code, suffix);
            assertThat(((Map<?, ?>) preview.get("counts")).get("refused"))
                    .as(code)
                    .isEqualTo(0);
            Map<String, Object> mapping = new LinkedHashMap<>();
            for (Object placeholder : (List<?>) preview.get("placeholders")) {
                Map<?, ?> wanted = (Map<?, ?>) placeholder;
                if (Boolean.TRUE.equals(wanted.get("required")))
                    mapping.put(String.valueOf(wanted.get("key")), List.of(first.id()));
            }
            assertThat(applied(number(preview.get("id")), mapping).get("status"))
                    .isEqualTo("applied");
        }
    }

    @Test
    void withoutTheRightTheAnswerIs403() throws Exception {
        assertThat(outsider.send(get("/api/v1/studio/templates")).getStatus()).isEqualTo(403);
        assertThat(outsider.send(get(API + "/imports")).getStatus()).isEqualTo(403);
        assertThat(outsider.send(post(API + "/imports"), json.readTree(Files.readAllBytes(ORDER)), null)
                        .getStatus())
                .isEqualTo(403);
        assertThat(outsider.send(post("/api/v1/studio/templates/order-approval/imports"), Map.of(), null)
                        .getStatus())
                .isEqualTo(403);
    }

    private Map<String, List<Long>> mapping() {
        return Map.of(
                "seller", List.of(seller),
                "approver", List.of(approver),
                "mailing-recipients", List.of(first.id(), second.id()));
    }

    private Map<String, Object> fromTemplate(String code, String withSuffix) throws Exception {
        MockHttpServletResponse response = importer.send(
                post("/api/v1/studio/templates/" + code + "/imports").param("suffix", withSuffix), Map.of(), null);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        assertThat(response.getHeader("Location")).startsWith(API + "/imports/");
        return remember(TestSession.object(response));
    }

    private Map<String, Object> upload(byte[] body, String withSuffix) throws Exception {
        MockHttpServletResponse response =
                importer.send(post(API + "/imports").param("suffix", withSuffix), json.readTree(body), null);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return remember(TestSession.object(response));
    }

    private MockHttpServletResponse apply(long id, Map<String, ?> mapping) throws Exception {
        return importer.send(post(API + "/imports/" + id + "/apply"), Map.of("mapping", mapping), null);
    }

    private Map<String, Object> applied(long id, Map<String, ?> mapping) throws Exception {
        MockHttpServletResponse response = apply(id, mapping);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return TestSession.object(response);
    }

    /** The forms the import will create, to archive them afterwards. */
    private Map<String, Object> remember(Map<String, Object> view) {
        for (Object item : (List<?>) view.get("items")) {
            Map<?, ?> entry = (Map<?, ?>) item;
            if ("form".equals(entry.get("kind"))) created.add(String.valueOf(entry.get("key")));
        }
        return view;
    }

    private long object(String kind) {
        return jdbc.sql("""
                        select object_id from studio_package_objects
                        where package_code = :scope and kind = :kind
                        """)
                .param("scope", "order-approval" + suffix)
                .param("kind", kind)
                .query(Long.class)
                .single();
    }

    private List<String> rights(long role) {
        return jdbc.sql("select form_code || '.' || action from md_role_permissions where role_id = :role")
                .param("role", role)
                .query(String.class)
                .list();
    }

    private static Map<String, String> outcomes(Map<String, Object> view) {
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (Object item : (List<?>) view.get("items")) {
            Map<?, ?> entry = (Map<?, ?>) item;
            outcomes.put(entry.get("kind") + ":" + entry.get("key"), String.valueOf(entry.get("outcome")));
        }
        return outcomes;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
