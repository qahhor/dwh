package com.smartup24.cms.instance.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.report.AggregateXlsxCells.Cell;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/** Independent totals compare JSON and actual XLSX cells under scope, sharing and UTC narrowing (ADR-0036). */
class AggregateExportGoldenHttpTest extends EmbeddedPostgresTest {
    private static final String CODE = "example.orders";
    private static final String ENTITY = "/api/v1/entities/" + CODE;
    private static final String VIEWS = "/api/v1/list-views/" + CODE;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModuleRegistryService modules;

    private TestSession owner;
    private TestSession reader;
    private String tag;
    private long readerRole;
    private long readerId;
    private long readerUnit;

    @BeforeEach
    void setup() throws Exception {
        modules.toggleModuleStatus("example", true);
        var users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        var rights = Map.of(
                CODE,
                Set.of("view", "create"),
                "md.profile",
                Set.of("view", "update"),
                "md.settings",
                Set.of("update"));
        owner = TestSession.signIn(
                context,
                users.withRights(rights, users.unit("gold-author-" + tag)).login());
        var viewer = users.withRights(rights, users.unit("gold-reader-" + tag));
        reader = TestSession.signIn(context, viewer.login());
        readerId = viewer.id();
        readerUnit = viewer.unit();
        readerRole = jdbc.sql("select role_id from md_user_roles where user_id = :id")
                .param("id", viewer.id())
                .query(Long.class)
                .single();
    }

    @Test
    void sharedDownloadUsesReaderTotalsAndImmediatelyHonorsShareRevocation() throws Exception {
        order(owner, "UZS", "999", "2026-10-01T00:30:00+05:00");
        order(reader, "UZS", "3", "2026-10-01T00:30:00+05:00");
        order(reader, "UZS", "5", "2026-10-01T01:30:00+05:00");
        order(reader, "USD", "7", "2026-10-01T06:00:00+05:00");
        long view = report();
        share(VIEWS + "/" + view, 0, List.of(readerRole));
        String path = ENTITY + "/reports/" + view;
        var json = reader.send(get(path));
        status(json, 200);
        var rows = rows(json);
        assertThat(rows).hasSize(2);
        assertRow(rows.get(0), "USD", 1, "14", "14");
        assertRow(rows.get(1), "UZS", 2, "16", "8");
        var download = reader.send(get(path + "/export"));
        status(download, 200);
        var cells = AggregateXlsxCells.data(download.getContentAsByteArray());
        assertThat(cells).containsEntry("B2", new Cell("text", "USD")).containsEntry("B3", new Cell("text", "UZS"));
        number(cells, "C2", "1");
        number(cells, "D2", "14");
        number(cells, "E2", "14");
        number(cells, "C3", "2");
        number(cells, "D3", "16");
        number(cells, "E3", "8");
        share(VIEWS + "/" + view, 1, List.of());
        status(reader.send(get(path)), 404);
        status(reader.send(get(path + "/export")), 404);
    }

    @Test
    void dashboardExportUsesSameUtcPeriodAndNeverFallsBackAfterSourceRevocation() throws Exception {
        order(reader, "UZS", "3", "2026-10-01T00:30:00+05:00");
        order(reader, "UZS", "5", "2026-10-01T01:30:00+05:00");
        order(reader, "USD", "7", "2026-10-01T06:00:00+05:00");
        long view = report();
        share(VIEWS + "/" + view, 0, List.of(readerRole));
        var created = owner.send(
                post("/api/v1/dashboards"),
                Map.of(
                        "name",
                        "UTC " + tag,
                        "widgets",
                        List.of(Map.of("viewId", view, "periodField", "modifiedAt", "width", 2))),
                null);
        status(created, 201);
        var board = TestSession.object(created);
        long revision = ((Number) board.get("revision")).longValue();
        String base = "/api/v1/dashboards/" + board.get("id");
        share(base, revision, List.of(readerRole));
        String widget = base + "/widgets/" + view;
        String expected = Long.toString(revision + 1);
        var json = reader.send(get(widget + "/result")
                .header("If-Match", expected)
                .param("from", "2026-09-30")
                .param("to", "2026-09-30"));
        status(json, 200);
        assertThat(rows(json)).hasSize(1);
        assertRow(rows(json).getFirst(), "UZS", 2, "16", "8");
        var download = reader.send(get(widget + "/export")
                .header("If-Match", expected)
                .param("from", "2026-09-30")
                .param("to", "2026-09-30"));
        status(download, 200);
        var cells = AggregateXlsxCells.data(download.getContentAsByteArray());
        assertThat(cells).containsEntry("B2", new Cell("text", "UZS")).doesNotContainKey("A3");
        number(cells, "D2", "16");
        number(cells, "E2", "8");
        status(reader.send(get(widget + "/export")), 428);
        status(reader.send(get(widget + "/export").header("If-Match", "99")), 409);
        share(VIEWS + "/" + view, 1, List.of());
        status(reader.send(get(widget + "/result").header("If-Match", expected)), 404);
        status(reader.send(get(widget + "/export").header("If-Match", expected)), 404);
    }

    @Test
    void truncationAndCalendarBucketsAgreeWithTheDownload() throws Exception {
        jdbc.sql("""
                insert into ex_orders (number, customer, order_date, org_unit_id, created_by, modified_by)
                select :tag || '-' || n, :tag, date '2020-01-01' + n, :unit, :user, :user
                from generate_series(0, 1000) as n
                """)
                .param("tag", tag)
                .param("unit", readerUnit)
                .param("user", readerId)
                .update();
        String grouping = "[{\"field\":\"orderDate\",\"trunc\":\"day\"}]";
        String filter = "[{\"field\":\"customer\",\"op\":\"eq\",\"value\":\"" + tag + "\"}]";
        var result =
                reader.send(get(ENTITY + "/report").param("groupBy", grouping).param("filter", filter));
        status(result, 200);
        assertThat(TestSession.object(result)).containsEntry("truncated", true);
        assertThat(rows(result)).hasSize(1000);
        assertThat(rows(result).getFirst().get("groups")).isEqualTo(List.of("2020-01-01"));
        assertThat(rows(result).getLast().get("groups")).isEqualTo(List.of("2022-09-26"));
        var file = reader.send(
                get(ENTITY + "/report/export").param("groupBy", grouping).param("filter", filter));
        status(file, 200);
        var cells = AggregateXlsxCells.data(file.getContentAsByteArray());
        assertThat(cells)
                .containsEntry("A2", new Cell("text", "2020-01-01"))
                .containsEntry("A1001", new Cell("text", "2022-09-26"))
                .doesNotContainKey("A1002");
        number(cells, "B2", "1");
        assertThat(AggregateXlsxCells.xml(file.getContentAsByteArray())).contains("truncated", "true");
    }

    private long report() throws Exception {
        var made = owner.send(
                post(VIEWS),
                Map.of(
                        "name",
                        "Golden " + tag,
                        "kind",
                        "report",
                        "state",
                        Map.of(
                                "groupBy",
                                List.of(Map.of("field", "status")),
                                "measures",
                                List.of(
                                        Map.of("op", "count"),
                                        Map.of("op", "sum", "field", "total"),
                                        Map.of("op", "avg", "field", "total")),
                                "filter",
                                List.of(Map.of("field", "customer", "op", "contains", "value", tag)),
                                "chart",
                                "table")),
                null);
        status(made, 201);
        return ((Number) TestSession.object(made).get("id")).longValue();
    }

    private void order(TestSession session, String currency, String price, String moment) throws Exception {
        var made = session.send(
                post(ENTITY),
                Map.of(
                        "customer",
                        "Golden " + tag,
                        "currency",
                        currency,
                        "orderDate",
                        "2026-10-01",
                        "lines",
                        List.of(Map.of("product", "Flour", "qty", "2", "price", price))));
        status(made, 201);
        jdbc.sql("update ex_orders set modified_at = cast(:moment as timestamptz) where id = :id")
                .param("moment", moment)
                .param("id", TestSession.object(made).get("id"))
                .update();
    }

    private void share(String path, long revision, List<Long> roles) throws Exception {
        status(
                owner.send(
                        put(path + "/sharing").header("If-Match", Long.toString(revision)),
                        Map.of("roleIds", roles),
                        null),
                200);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(MockHttpServletResponse response) throws Exception {
        return (List<Map<String, Object>>) TestSession.object(response).get("rows");
    }

    private static void assertRow(Map<String, Object> row, String currency, int count, String sum, String average) {
        assertThat(row.get("groups")).isEqualTo(List.of("draft", currency));
        var values = (List<?>) row.get("values");
        assertThat(((Number) values.get(0)).intValue()).isEqualTo(count);
        assertThat(new BigDecimal(values.get(1).toString())).isEqualByComparingTo(sum);
        assertThat(new BigDecimal(values.get(2).toString())).isEqualByComparingTo(average);
    }

    private static void number(Map<String, Cell> cells, String address, String expected) {
        assertThat(cells.get(address).type()).isEqualTo("number");
        assertThat(new BigDecimal(cells.get(address).value())).isEqualByComparingTo(expected);
    }

    private static void status(MockHttpServletResponse response, int expected) throws Exception {
        assertThat(response.getStatus()).as("HTTP response status").isEqualTo(expected);
    }
}
