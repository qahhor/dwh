package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioDocumentFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.report.export.ReportExportJob;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The details of documents of live forms (ADR-0043): a number given on approval, the approvers told within the data
 * scope of the form, a comment bounded in length, the references of lines checked against their targets, the lines in
 * the XLSX export, new versions that grow a document and refuse what records hold, and the editor's checks of the
 * formulas of lines and transitions.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioDocumentDetailsHttpTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ReportExportJob exportJob;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private TestUsers users;
    private String tag;
    private long unit;
    private TestSession publisher;

    @BeforeEach
    void setup() throws Exception {
        users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        unit = users.unit("details-" + tag);
        publisher = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        "studio.forms", Set.of("publish"),
                                        "md.settings", Set.of("update"),
                                        "studio.lists", Set.of("view", "manage")),
                                unit)
                        .login());
    }

    @Test
    void anApprovedOrderTakesItsNumberTheApproversOfItsUnitAreToldAndTheExportHoldsItsLines() throws Exception {
        String products = "wares_" + tag;
        forms.list(publisher, products, List.of("tea", "coffee"));
        String clients = forms.activate(publisher, forms.clients("customers_" + tag));
        Map<String, Object> declaration = order("approvals_" + tag, clients, products);
        entity(declaration).put("scope", "org_unit");
        entity(declaration)
                .put("numbering", Map.of("pattern", "ЗК-{YYYY}-{NNNNNN}", "reset", "year", "assignOn", "approved"));
        String code = forms.activate(publisher, declaration);
        TestUsers.TestUser authorUser = users.withRights(
                Map.of(code, Set.of("view", "create", "update", "submit"), clients, Set.of("view", "create")), unit);
        TestSession author = TestSession.signIn(context, authorUser.login());
        TestUsers.TestUser near = users.withRights(Map.of(code, Set.of("view", "approve", "reject")), unit);
        TestUsers.TestUser far =
                users.withRights(Map.of(code, Set.of("view", "approve", "reject")), users.unit("far-" + tag));
        TestSession manager = TestSession.signIn(context, near.login());
        long client = forms.create(author, clients, Map.of("name", "Клиент", "code", "c3"));

        Map<String, Object> created = create(author, code, client, List.of(line("tea", "2", "3", null)));
        assertThat(created.get("docNumber")).isNull();
        long order = id(created);
        assertThat(transition(author, code, order, "submit", 1, Map.of()).getStatus())
                .isEqualTo(200);
        assertThat(notices(jdbc, near.id(), code, order)).isEqualTo(1);
        assertThat(notices(jdbc, far.id(), code, order)).isZero();

        MockHttpServletResponse tooLong =
                transition(manager, code, order, "approve", 2, Map.of("comment", "x".repeat(1001)));
        assertThat(tooLong.getStatus()).isEqualTo(422);
        assertThat(tooLong.getContentAsString()).contains("too_long");
        MockHttpServletResponse approved = transition(manager, code, order, "approve", 2, Map.of("comment", "Ок"));
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        String year = String.valueOf(LocalDate.now(ZoneId.of(ZONE)).getYear());
        assertThat(TestSession.object(approved).get("docNumber")).isEqualTo("ЗК-" + year + "-000001");

        MockHttpServletResponse queued = author.send(
                post("/api/v1/exports"), Map.of("list", code, "columns", List.of("docNumber", "total"), "lang", "ru"));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        String exportId = (String) TestSession.object(queued).get("id");
        runQueuedExport(exportId);
        MockHttpServletResponse file = author.send(get("/api/v1/exports/" + exportId + "/file"));
        assertThat(file.getStatus()).isEqualTo(200);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            List<Sheet> sheets = workbook.getSheets().toList();
            assertThat(sheets).hasSize(2);
            List<Row> lines = sheets.get(1).read();
            assertThat(lines).hasSize(2);
            assertThat(lines.get(1).getCellText(0)).isEqualTo("ЗК-" + year + "-000001");
            assertThat(lines.get(1).getCellText(1)).isEqualTo("1");
            assertThat(lines.get(1).getCellText(2)).isEqualTo("Тип tea");
        }
    }

    @Test
    void theReferencesOfLinesNameVisibleRowsAndItemsInUse() throws Exception {
        String products = "stock_" + tag;
        forms.list(publisher, products, List.of("tea", "coffee"));
        String clients = forms.activate(publisher, forms.clients("partners_" + tag));
        Map<String, Object> declaration = order("checked_" + tag, clients, products);
        columns(declaration)
                .add(column("buyer", "ref", false, Map.of("target", Map.of("kind", "form", "code", clients))));
        String code = forms.activate(publisher, declaration);
        TestSession writer = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(code, Set.of("view", "create", "update"), clients, Set.of("view", "create")),
                                unit)
                        .login());
        long client = forms.create(writer, clients, Map.of("name", "Клиент", "code", "c4"));
        forms.deactivate(publisher, products, "coffee");

        Map<String, Object> archived = line("coffee", "1", "1", null);
        MockHttpServletResponse refused =
                writer.send(post("/api/v1/entities/" + code), Map.of("client", client, "items", List.of(archived)));
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("items[0].product").contains("archived");

        Map<String, Object> stranger = line("tea", "1", "1", null);
        stranger.put("buyer", Long.MAX_VALUE);
        MockHttpServletResponse missing =
                writer.send(post("/api/v1/entities/" + code), Map.of("client", client, "items", List.of(stranger)));
        assertThat(missing.getStatus()).isEqualTo(422);
        assertThat(missing.getContentAsString()).contains("items[0].buyer").contains("not_found");

        Map<String, Object> named = line("tea", "1", "1", null);
        named.put("buyer", client);
        Map<String, Object> kept = create(writer, code, client, List.of(named));
        String card =
                writer.send(get("/api/v1/entities/" + code + "/" + id(kept))).getContentAsString();
        assertThat(card).contains("Клиент");
    }

    @Test
    void aNewVersionGrowsTheDocumentButKeepsItsStatusesTablesAndNumber() throws Exception {
        String products = "items_" + tag;
        forms.list(publisher, products, List.of("tea"));
        String clients = forms.activate(publisher, forms.clients("folks_" + tag));
        String code = forms.activate(publisher, order("growing_" + tag, clients, products));

        Map<String, Object> grown = order("growing_" + tag, clients, products);
        columns(grown).add(column("note", "text", false, Map.of("maxLength", 200)));
        states(grown).add(state("onhold", "Отложен", "orange", Map.of()));
        transitions(grown).add(StudioDocumentFixtures.transition("hold", "Отложить", "pending", "onhold", Map.of()));
        MockHttpServletResponse accepted = version(code, grown, 1);
        assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(201);
        Integer hold = jdbc.sql("select count(*) from md_form_actions where form_code = :form and action = 'hold'")
                .param("form", code)
                .query(Integer.class)
                .single();
        assertThat(hold).isEqualTo(1);

        Map<String, Object> shrunk = order("growing_" + tag, clients, products);
        states(shrunk).removeLast();
        transitions(shrunk).removeLast();
        entity(shrunk).put("numbering", Map.of("pattern", "N-{YYYY}-{NNNN}", "reset", "year"));
        MockHttpServletResponse refused = version(code, shrunk, 2);
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString())
                .contains("change_state_removed")
                .contains("change_numbering")
                .contains("lines.items.note");
    }

    @Test
    void theEditorChecksTheFormulasOfLinesAndTransitions() throws Exception {
        List<Map<String, Object>> header = List.of(
                Map.of("id", "discount", "type", "number"), Map.of("id", "total", "type", "number", "computed", true));
        List<Map<String, Object>> lines = List.of(Map.of(
                "key",
                "items",
                "fields",
                List.of(Map.of("id", "qty", "type", "number"), Map.of("id", "amount", "type", "number"))));
        assertThat(check(header, lines, "qty * doc.discount", "formula", "amount", "items", Map.of()))
                .containsEntry("valid", true)
                .containsEntry("type", "number");
        assertThat(check(header, lines, "qty * doc.total", "formula", "amount", "items", Map.of()))
                .containsEntry("reason", "unknown_field");
        assertThat(check(header, lines, "sum(lines.items.amount)", "formula", "total", null, Map.of()))
                .containsEntry("type", "number");
        assertThat(check(
                        header,
                        lines,
                        "docStatus = \"draft\" and docNumber != \"\" and count(lines.items) > 0",
                        "condition",
                        null,
                        null,
                        Map.of("numbered", true, "statuses", true)))
                .containsEntry("type", "bool");
        assertThat(check(header, lines, "total", "condition", null, null, Map.of()))
                .containsEntry("reason", "result");
        assertThat(check(header, lines, "qty", "formula", "amount", "other", Map.of()))
                .containsEntry("reason", "result");
    }

    private Map<String, Object> check(
            List<Map<String, Object>> fields,
            List<Map<String, Object>> lines,
            String formula,
            String kind,
            String field,
            String line,
            Map<String, Object> extra)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", fields);
        body.put("lines", lines);
        body.put("formula", formula);
        body.put("kind", kind);
        if (field != null) body.put("field", field);
        if (line != null) body.put("line", line);
        body.putAll(extra);
        MockHttpServletResponse response = publisher.send(post("/api/v1/studio/formulas/check"), body, null);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return TestSession.object(response);
    }

    private MockHttpServletResponse version(String code, Map<String, Object> declaration, long revision)
            throws Exception {
        MockHttpServletResponse prepared =
                publisher.send(post("/api/v1/form-studio/new-forms/prepare"), declaration, null);
        assertThat(prepared.getStatus()).as(prepared.getContentAsString()).isEqualTo(200);
        return publisher.send(
                post("/api/v1/studio/forms/" + code + "/versions").header("If-Match", "\"" + revision + "\""),
                TestSession.object(prepared),
                null);
    }

    private void runQueuedExport(String id) throws Exception {
        String raw = jdbc.sql("""
                        select args::text from fnd_job_queue
                         where handler = 'report.export' and args ->> 'exportId' = :id
                        """).param("id", id).query(String.class).single();
        Map<String, Object> args = json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
                        assertThat(SecurityContext.isAuthenticated()).isFalse();
                        exportJob.run(args);
                    })
                    .get(30, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entity(Map<String, Object> declaration) {
        Map<String, Object> entity = new LinkedHashMap<>((Map<String, Object>) declaration.get("entity"));
        declaration.put("entity", entity);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> columns(Map<String, Object> declaration) {
        List<Object> tables = new ArrayList<>((List<Object>) declaration.get("lines"));
        Map<String, Object> table = new LinkedHashMap<>((Map<String, Object>) tables.getFirst());
        List<Object> columns = new ArrayList<>((List<Object>) table.get("fields"));
        table.put("fields", columns);
        tables.set(0, table);
        declaration.put("lines", tables);
        return columns;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> states(Map<String, Object> declaration) {
        return part(declaration, "states");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> transitions(Map<String, Object> declaration) {
        return part(declaration, "transitions");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> part(Map<String, Object> declaration, String key) {
        Map<String, Object> workflow = new LinkedHashMap<>((Map<String, Object>) declaration.get("workflow"));
        List<Object> items = new ArrayList<>((List<Object>) workflow.get(key));
        workflow.put(key, items);
        declaration.put("workflow", workflow);
        return items;
    }
}
