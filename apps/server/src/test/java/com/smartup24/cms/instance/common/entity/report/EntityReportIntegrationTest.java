package com.smartup24.cms.instance.common.entity.report;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.example.service.ExampleOrderEntity;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * Plan 10/10, item 5.8, acceptance "a report or a widget without Java code" (ADR-0032, 10.2), end to end: a report is
 * a request or a saved view of an entity's list, and it never counts a record or a value its viewer could not read in
 * the list — the org-unit scope of the reference orders and the archive go into its SQL, a field with a right the
 * viewer lacks is no field of the report, and a saved report is its owner's alone.
 */
@Import(EntityReportIntegrationTest.Fixture.class)
class EntityReportIntegrationTest extends EmbeddedPostgresTest {

    static final String NOTES = "ms.reportnotes";
    static final String SECRET_FORM = "md.modules";
    static final String SECRET_ACTION = "view";

    private static final String ORDERS = "/api/v1/entities/" + ExampleOrderEntity.CODE;
    private static final Map<String, Set<String>> CLERK = Map.of(
            ExampleOrderEntity.CODE,
            Set.of("view", "create", "update", "post"),
            "md.profile",
            Set.of("view", "update"));

    /** The note table seen through a declaration whose colour needs a right the plain reader lacks. */
    @TestConfiguration
    static class Fixture {

        static final EntityDefinition SECRET = Entity.define(NOTES, "notes")
                .table("ms_notes", "n")
                .scope(EntityScope.owner("created_by"))
                .field(text("title", "notes.col.title")
                        .column("title")
                        .required()
                        .list(sortable()))
                .field(select("color", "notes.col.color", MsNoteEntity.COLORS, "notes.color_")
                        .column("color")
                        .requires(SECRET_FORM, SECRET_ACTION))
                .field(bool("isPinned", "notes.col.pinned").column("is_pinned"))
                .field(instant("modifiedAt", "notes.col.modified_at")
                        .system(SystemColumn.MODIFIED_AT)
                        .list(sortable()))
                .section("main", "entity.section.main", "title", "color", "isPinned")
                .defaultSort("modifiedAt", Entity.Sort.DESC)
                .archivable()
                .capabilities(EntityCapability.SAVED_VIEWS)
                .build();

        @Bean
        EntityDefinition reportNotesEntity() {
            return SECRET;
        }
    }

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModuleRegistryService modules;

    private TestUsers users;
    private String tag;
    private TestUser clerk;
    private TestSession session;

    @BeforeEach
    void signIn() throws Exception {
        modules.toggleModuleStatus("example", true);
        users = TestUsers.of(wac);
        tag = UUID.randomUUID().toString().substring(0, 8);
        clerk = users.withRights(CLERK, users.unit("north-" + tag));
        session = TestSession.signIn(wac, clerk.login());
    }

    @Test
    @DisplayName("5.8: totals per status and currency count only the orders of the viewer's unit")
    void totalsFollowTheScope() throws Exception {
        order(session, "UZS", "3", "10.00");
        Map<String, Object> posted = order(session, "UZS", "1", "7.40");
        order(session, "USD", "2", "5.00");
        assertThat(session.send(post(ORDERS + "/" + posted.get("id") + "/actions/post")
                                .header("If-Match", "\"1\""))
                        .getStatus())
                .isEqualTo(200);
        TestUser south = users.withRights(CLERK, users.unit("south-" + tag));
        TestSession other = TestSession.signIn(wac, south.login());
        order(other, "UZS", "1", "100.00");

        Map<String, Object> report = report(
                session,
                ORDERS,
                "[{\"field\":\"status\"}]",
                "[{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"total\"}]",
                customer());

        assertThat(report.get("groups"))
                .isEqualTo(List.of(
                        Map.of("field", "status", "implicit", false),
                        Map.of("field", "totalCurrency", "implicit", true)));
        assertThat(report.get("measures"))
                .isEqualTo(List.of(Map.of("op", "count"), Map.of("op", "sum", "field", "total")));
        assertThat(rows(report))
                .containsExactly(
                        row(List.of("draft", "USD"), 1, 10.0),
                        row(List.of("draft", "UZS"), 1, 30.0),
                        row(List.of("posted", "UZS"), 1, 7.4));
        assertThat(report).containsEntry("truncated", false);

        Map<String, Object> theirs = report(other, ORDERS, null, null, customer());
        assertThat(rows(theirs)).containsExactly(row(List.of(), 1));
    }

    @Test
    @DisplayName("5.8: a date groups by its month, a reference by its row key")
    void datesAndReferencesGroup() throws Exception {
        order(session, "UZS", "1", "1.00");
        order(session, "UZS", "1", "2.00");

        Map<String, Object> report = report(
                session,
                ORDERS,
                "[{\"field\":\"orderDate\",\"trunc\":\"month\"},{\"field\":\"orgUnitId\"}]",
                "[{\"op\":\"count\"},{\"op\":\"max\",\"field\":\"total\"}]",
                customer());

        String month = LocalDate.now().withDayOfMonth(1).toString();
        assertThat(rows(report)).containsExactly(row(List.of(month, Math.toIntExact(clerk.unit()), "UZS"), 2, 2.0));
    }

    @Test
    @DisplayName("5.8: a field with a right the viewer lacks is no field of the report; the holder groups by it")
    void fieldRightsHoldInReports() throws Exception {
        TestUser plain = users.withRights(Map.of("notes", Set.of("view")), clerk.unit());
        TestUser holder =
                users.withRights(Map.of("notes", Set.of("view"), SECRET_FORM, Set.of(SECRET_ACTION)), clerk.unit());
        note(plain, "red", true);
        note(plain, "red", false);
        note(holder, "blue", false);
        TestSession plainSession = TestSession.signIn(wac, plain.login());
        String path = "/api/v1/entities/" + NOTES;

        MockHttpServletResponse refused = plainSession.send(reportRequest(path, "[{\"field\":\"color\"}]", null, null));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(refused.getContentAsString())
                .contains("\"field\":\"groupBy[0].field\"", "QUERY_UNKNOWN_FIELD")
                .doesNotContain("red");
        MockHttpServletResponse filtered = plainSession.send(
                reportRequest(path, null, null, "[{\"field\":\"color\",\"op\":\"eq\",\"value\":\"red\"}]"));
        assertThat(filtered.getStatus()).isEqualTo(422);

        Map<String, Object> pinned = report(plainSession, path, "[{\"field\":\"isPinned\"}]", null, null);
        assertThat(rows(pinned)).containsExactly(row(List.of(false), 1), row(List.of(true), 1));

        Map<String, Object> colours =
                report(TestSession.signIn(wac, holder.login()), path, "[{\"field\":\"color\"}]", null, null);
        assertThat(rows(colours)).containsExactly(row(List.of("blue"), 1));
    }

    @Test
    @DisplayName("5.8: an archived record leaves the totals until the report filters on the archive")
    void archivedRecordsLeaveTheTotals() throws Exception {
        TestUser reader = users.withRights(Map.of("notes", Set.of("view")), clerk.unit());
        note(reader, "green", false);
        long archived = note(reader, "green", false);
        jdbc.sql("update ms_notes set archived_at = now(), archived_by = created_by where id = :id")
                .param("id", archived)
                .update();
        TestSession reading = TestSession.signIn(wac, reader.login());
        String path = "/api/v1/entities/" + NOTES;

        assertThat(rows(report(reading, path, null, null, null))).containsExactly(row(List.of(), 1));
        assertThat(rows(report(
                        reading,
                        path,
                        "[{\"field\":\"archived\"}]",
                        null,
                        "[{\"field\":\"archived\"," + "\"op\":\"eq\",\"value\":true}]")))
                .containsExactly(row(List.of(true), 1));
    }

    @Test
    @DisplayName("5.8: an entity the viewer may not see answers 404, as its list does")
    void unseenEntityAnswersNotFound() throws Exception {
        TestUser stranger = users.withRights(Map.of(), clerk.unit());
        TestSession strangerSession = TestSession.signIn(wac, stranger.login());

        assertThat(strangerSession.send(reportRequest(ORDERS, null, null, null)).getStatus())
                .isEqualTo(404);
        assertThat(strangerSession.send(get(ORDERS + "/reports/1")).getStatus()).isEqualTo(404);
        assertThat(session.send(reportRequest("/api/v1/entities/no.such", null, null, null))
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("5.8: a report saved as a view runs for its owner, appears as a widget, and is nobody else's")
    void savedReportsAndWidgets() throws Exception {
        order(session, "UZS", "2", "3.00");
        String views = "/api/v1/list-views/" + ExampleOrderEntity.CODE;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("groupBy", List.of(Map.of("field", "status")));
        state.put("measures", List.of(Map.of("op", "count")));
        state.put("filter", List.of(Map.of("field", "customer", "op", "contains", "value", tag)));
        state.put("chart", "bar");

        MockHttpServletResponse saved = session.send(post(views), view("By status " + tag, state, "widget"));
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(201);
        Map<String, Object> widget = TestSession.object(saved);
        assertThat(widget).containsEntry("kind", "widget").containsEntry("isDefault", false);
        assertThat(widget.get("state").toString()).contains("chart=bar");
        Number id = (Number) widget.get("id");

        MockHttpServletResponse run = session.send(get(ORDERS + "/reports/" + id));
        assertThat(run.getStatus()).as(run.getContentAsString()).isEqualTo(200);
        assertThat(rows(TestSession.object(run))).containsExactly(row(List.of("draft"), 1));

        List<Object> widgets = TestSession.array(session.send(get("/api/v1/report-widgets")));
        assertThat(widgets)
                .singleElement()
                .satisfies(item -> assertThat(TestSession.JSON.convertValue(item, Map.class))
                        .containsEntry("entity", ExampleOrderEntity.CODE)
                        .containsEntry("listCode", ExampleOrderEntity.CODE)
                        .containsEntry("name", "By status " + tag));
        assertThat(TestSession.array(session.send(get(views)))).isEmpty();
        assertThat(TestSession.array(session.send(get(views).param("kind", "report", "widget"))))
                .hasSize(1);

        TestSession neighbour =
                TestSession.signIn(wac, users.withRights(CLERK, clerk.unit()).login());
        assertThat(neighbour.send(get(ORDERS + "/reports/" + id)).getStatus()).isEqualTo(404);
        assertThat(TestSession.array(neighbour.send(get("/api/v1/report-widgets"))))
                .isEmpty();

        MockHttpServletResponse table = session.send(post(views), view("Table " + tag, Map.of(), null));
        assertThat(table.getStatus()).as(table.getContentAsString()).isEqualTo(201);
        assertThat(session.send(get(
                                ORDERS + "/reports/" + TestSession.object(table).get("id")))
                        .getStatus())
                .isEqualTo(404);

        Map<String, Object> asReport = view("By status " + tag, state, "report");
        asReport.put("lockVersion", widget.get("lockVersion"));
        MockHttpServletResponse unpinned = session.send(put(views + "/" + id), asReport);
        assertThat(unpinned.getStatus()).as(unpinned.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.array(session.send(get("/api/v1/report-widgets"))))
                .isEmpty();
    }

    @Test
    @DisplayName("5.8: a report view is checked as it would run, never opens a list and lives on entity lists only")
    void reportViewsAreChecked() throws Exception {
        String views = "/api/v1/list-views/" + ExampleOrderEntity.CODE;
        MockHttpServletResponse bad = session.send(
                post(views),
                view(
                        "Bad " + tag,
                        Map.of(
                                "groupBy", List.of(Map.of("field", "customer")),
                                "measures", List.of(Map.of("op", "sum", "field", "status")),
                                "chart", "pie",
                                "columns", Map.of()),
                        "report"));
        assertThat(bad.getStatus()).isEqualTo(422);
        assertThat(bad.getContentAsString())
                .contains("state.groupBy[0].field", "state.measures[0].field", "state.chart", "state.columns");

        Map<String, Object> defaulted = view("Default " + tag, Map.of("chart", "kpi"), "report");
        defaulted.put("isDefault", true);
        MockHttpServletResponse notDefault = session.send(post(views), defaulted);
        assertThat(notDefault.getStatus()).isEqualTo(422);
        assertThat(notDefault.getContentAsString()).contains("\"field\":\"isDefault\"");

        TestUser auditor = users.withRights(
                Map.of("upl.sources", Set.of("view"), "md.profile", Set.of("view", "update")), clerk.unit());
        MockHttpServletResponse notEntity = TestSession.signIn(wac, auditor.login())
                .send(post("/api/v1/list-views/upl.sources"), view("Report " + tag, Map.of(), "report"));
        assertThat(notEntity.getStatus()).as(notEntity.getContentAsString()).isEqualTo(422);
        assertThat(notEntity.getContentAsString()).contains("\"field\":\"kind\"");
        assertThat(session.send(get(views).param("kind", "chart")).getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("5.8: a dashboard holds at most 12 widgets of one person")
    void widgetsAreLimited() throws Exception {
        String views = "/api/v1/list-views/" + ExampleOrderEntity.CODE;
        for (int i = 0; i < 12; i++) {
            assertThat(session.send(post(views), view("W" + i + " " + tag, Map.of("chart", "kpi"), "widget"))
                            .getStatus())
                    .isEqualTo(201);
        }
        MockHttpServletResponse thirteenth =
                session.send(post(views), view("W12 " + tag, Map.of("chart", "kpi"), "widget"));
        assertThat(thirteenth.getStatus()).isEqualTo(422);
        assertThat(thirteenth.getContentAsString()).contains("error.md.widget_limit");
        assertThat(TestSession.array(session.send(get("/api/v1/report-widgets"))))
                .hasSize(12);
    }

    private String customer() {
        return "[{\"field\":\"customer\",\"op\":\"contains\",\"value\":\"" + tag + "\"}]";
    }

    private Map<String, Object> order(TestSession as, String currency, String qty, String price) throws Exception {
        MockHttpServletResponse response = as.send(
                post(ORDERS),
                Map.of(
                        "customer",
                        "Customer " + tag,
                        "currency",
                        currency,
                        "lines",
                        List.of(Map.of("product", "Flour", "qty", qty, "price", price))));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return TestSession.object(response);
    }

    private long note(TestUser owner, String color, boolean pinned) {
        return jdbc.sql("""
                        insert into ms_notes (title, content_md, color, is_pinned, created_by, modified_by)
                        values (:title, '', :color, :pinned, :owner, :owner) returning id
                        """)
                .param("title", "Report " + tag)
                .param("color", color)
                .param("pinned", pinned)
                .param("owner", owner.id())
                .query(Long.class)
                .single();
    }

    private static MockHttpServletRequestBuilder reportRequest(
            String entityPath, String groupBy, String measures, String filter) {
        MockHttpServletRequestBuilder request = get(entityPath + "/report");
        if (groupBy != null) request.param("groupBy", groupBy);
        if (measures != null) request.param("measures", measures);
        if (filter != null) request.param("filter", filter);
        return request;
    }

    private static Map<String, Object> report(
            TestSession as, String entityPath, String groupBy, String measures, String filter) throws Exception {
        MockHttpServletResponse response = as.send(reportRequest(entityPath, groupBy, measures, filter));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return TestSession.object(response);
    }

    private static Map<String, Object> view(String name, Object state, String kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("state", state);
        body.put("isDefault", false);
        if (kind != null) body.put("kind", kind);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rows(Map<String, Object> report) {
        return (List<Object>) report.get("rows");
    }

    private static Map<String, Object> row(List<?> groups, Object... values) {
        return Map.of("groups", groups, "values", List.of(values));
    }
}
