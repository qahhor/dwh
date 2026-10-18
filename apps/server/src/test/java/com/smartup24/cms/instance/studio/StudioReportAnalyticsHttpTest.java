package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioFormFixtures.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR-0044 over the server alone, on a live form of orders seen by org unit: a pivot of regions by month with totals and
 * shares, distinct clients per region, a comparison of two periods, a saved report with a required period and a region
 * parameter shared with a role — and the reader of another unit who runs the same report sees only the orders of their
 * unit. The XLSX of the pivot is its matrix with the region names and the totals.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioReportAnalyticsHttpTest extends EmbeddedPostgresTest {
    private static final String PIVOT = "[{\"field\":\"region\"},{\"field\":\"orderdate\",\"trunc\":\"month\"}]";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private String tag;
    private String orders;
    private TestSession north;
    private TestSession south;
    private TestUser southUser;

    @BeforeEach
    void setup() throws Exception {
        TestUsers users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        long unitA = users.unit("north-" + tag);
        long unitB = users.unit("south-" + tag);
        TestSession publisher = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        "studio.forms", Set.of("publish"),
                                        "studio.lists", Set.of("view", "manage"),
                                        "md.settings", Set.of("update")),
                                unitA)
                        .login());
        String regions = "aregions_" + tag;
        forms.list(publisher, regions, List.of("north", "south", "east"));
        String clients = forms.activate(publisher, forms.clients("aclients_" + tag));
        orders = forms.activate(
                publisher,
                forms.format4(
                        forms.plain(
                                "aorders_" + tag,
                                "Заказы",
                                "org_unit",
                                List.of(
                                        field(
                                                "region",
                                                "ref",
                                                true,
                                                Map.of("target", Map.of("kind", "list", "code", regions))),
                                        field(
                                                "client",
                                                "ref",
                                                true,
                                                Map.of("target", Map.of("kind", "form", "code", clients))),
                                        field("orderdate", "date", true, Map.of()),
                                        field("total", "number", false, Map.of("scale", 2)))),
                        null,
                        null));
        Map<String, Set<String>> rights = Map.of(
                orders,
                Set.of("view", "create"),
                clients,
                Set.of("view", "create"),
                "md.profile",
                Set.of("view", "update"),
                "md.settings",
                Set.of("update"));
        north = TestSession.signIn(context, users.withRights(rights, unitA).login());
        southUser = users.withRights(rights, unitB);
        south = TestSession.signIn(context, southUser.login());
        long c1 = forms.create(north, clients, Map.of("name", "К1 " + tag, "code", "k1" + tag));
        long c2 = forms.create(north, clients, Map.of("name", "К2 " + tag, "code", "k2" + tag));
        long c3 = forms.create(north, clients, Map.of("name", "К3 " + tag, "code", "k3" + tag));
        order(north, "north", c1, "2026-08-05", "100");
        order(north, "north", c1, "2026-09-10", "50");
        order(north, "north", c2, "2026-09-20", "30");
        order(north, "east", c3, "2026-09-25", "20");
        order(south, "south", c1, "2026-09-01", "500");
        order(south, "north", c2, "2026-09-02", "700");
    }

    private void order(TestSession writer, String region, long client, String date, String total) throws Exception {
        forms.create(writer, orders, Map.of("region", region, "client", client, "orderdate", date, "total", total));
    }

    @Test
    void aPivotOfRegionsByMonthWithTotalsAndSharesInTheReadersUnit() throws Exception {
        Map<String, Object> pivot = report(
                north,
                Map.of(
                        "groupBy",
                        PIVOT,
                        "measures",
                        "[{\"op\":\"sum\",\"field\":\"total\"},{\"op\":\"sum\",\"field\":\"total\","
                                + "\"share\":\"total\"}]",
                        "pivot",
                        "true"));
        assertThat(pivot.get("pivot")).isEqualTo(true);
        assertThat(cells(pivot))
                .containsExactly(
                        List.of("east", "2026-09-01"), List.of("north", "2026-08-01"), List.of("north", "2026-09-01"));
        List<Map<String, Object>> totals = totals(pivot);
        assertThat(totals)
                .extracting(total -> total.get("kind"))
                .containsExactly("row", "row", "column", "column", "grand");
        assertThat(totals.get(1)).satisfies(row -> {
            assertThat(labels(row)).first().isEqualTo("Тип north");
            assertThat(values(row)).containsExactly(180.0, 90.0);
        });
        assertThat(values(totals.get(4))).containsExactly(200.0, 100.0);

        Map<String, Object> distinct = report(
                north,
                Map.of(
                        "groupBy", "[{\"field\":\"region\"}]",
                        "measures", "[{\"op\":\"count_distinct\",\"field\":\"client\"},{\"op\":\"count\"}]",
                        "totals", "true"));
        assertThat(values(totals(distinct).getFirst())).containsExactly(3.0, 4.0);

        Map<String, Object> other = report(south, Map.of("groupBy", PIVOT, "pivot", "true"));
        assertThat(cells(other)).containsExactly(List.of("north", "2026-09-01"), List.of("south", "2026-09-01"));
    }

    @Test
    void twoPeriodsAreComparedWithTheChange() throws Exception {
        Map<String, Object> compared = report(
                north,
                Map.of(
                        "measures",
                        "[{\"op\":\"sum\",\"field\":\"total\"}]",
                        "compare",
                        "{\"field\":\"orderdate\",\"period\":\"custom\",\"from\":\"2026-09-01\","
                                + "\"to\":\"2026-09-30\"}"));
        @SuppressWarnings("unchecked")
        Map<String, Object> periods = (Map<String, Object>) compared.get("comparison");
        assertThat(periods).containsEntry("previousFrom", "2026-08-02").containsEntry("previousTo", "2026-08-31");
        assertThat(values(rows(compared).getFirst())).containsExactly(100.0, 100.0, 0.0, 0.0);
        MockHttpServletResponse grouped = north.send(get("/api/v1/entities/" + orders + "/report")
                .param("groupBy", "[{\"field\":\"orderdate\"}]")
                .param("compare", "{\"field\":\"orderdate\",\"period\":\"month\"}"));
        assertThat(grouped.getStatus()).isEqualTo(422);
        assertThat(grouped.getContentAsString()).contains("compare.field");
    }

    @Test
    void aSavedReportWithParametersSharedWithAnotherUnitShowsEachReaderTheirOrders() throws Exception {
        Map<String, Object> state = Map.of(
                "groupBy", List.of(Map.of("field", "region")),
                "measures", List.of(Map.of("op", "sum", "field", "total")),
                "filter", List.of(),
                "chart", "table",
                "totals", true,
                "parameters",
                        List.of(
                                Map.of("key", "period", "field", "orderdate", "required", true),
                                Map.of("key", "region", "field", "region", "default", List.of("north", "south"))));
        MockHttpServletResponse saved = north.send(
                post("/api/v1/list-views/" + orders),
                Map.of("name", "Параметры " + tag, "kind", "report", "state", state, "isDefault", false),
                null);
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(201);
        Map<String, Object> view = TestSession.object(saved);
        long id = ((Number) view.get("id")).longValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parameters =
                (List<Map<String, Object>>) ((Map<String, Object>) view.get("state")).get("parameters");
        assertThat(parameters)
                .extracting(parameter -> parameter.get("required"))
                .containsExactly(true, false);

        MockHttpServletResponse missing = north.send(get("/api/v1/entities/" + orders + "/reports/" + id));
        assertThat(missing.getStatus()).isEqualTo(422);
        assertThat(missing.getContentAsString())
                .contains("parameters.period", "error.common.report_parameter_required");

        String september = "{\"period\":{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}}";
        Map<String, Object> mine = saved(north, id, september);
        assertThat(groups(mine)).containsExactly("north");
        assertThat(values(rows(mine).getFirst())).containsExactly(80.0);
        Map<String, Object> east = saved(north, id, "{\"period\":{\"preset\":\"this_year\"},\"region\":[\"east\"]}");
        assertThat(groups(east)).containsExactly("east");
        MockHttpServletResponse unknown =
                north.send(get("/api/v1/entities/" + orders + "/reports/" + id).param("parameters", "{\"x\":1}"));
        assertThat(unknown.getStatus()).isEqualTo(422);
        assertThat(unknown.getContentAsString()).contains("parameters.x");
        MockHttpServletResponse wrong = north.send(get("/api/v1/entities/" + orders + "/reports/" + id)
                .param("parameters", "{\"period\":{\"from\":\"2026-09-30\",\"to\":\"2026-09-01\"}}"));
        assertThat(wrong.getStatus()).isEqualTo(422);

        long role = jdbc.sql("select role_id from md_user_roles where user_id = :id")
                .param("id", southUser.id())
                .query(Long.class)
                .single();
        MockHttpServletResponse shared = north.send(
                put("/api/v1/list-views/" + orders + "/" + id + "/sharing").header("If-Match", "0"),
                Map.of("roleIds", List.of(role)),
                null);
        assertThat(shared.getStatus()).as(shared.getContentAsString()).isEqualTo(200);
        Map<String, Object> theirs = saved(south, id, september);
        assertThat(groups(theirs)).containsExactly("north", "south");
        assertThat(values(rows(theirs).getFirst())).containsExactly(700.0);
        assertThat(values(totals(theirs).getFirst())).containsExactly(1200.0);

        MockHttpServletResponse file = south.send(get("/api/v1/entities/" + orders + "/reports/" + id + "/export")
                .param("parameters", september)
                .param("lang", "en"));
        assertThat(file.getStatus()).isEqualTo(200);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows).hasSize(4);
            assertThat(rows.get(1).getCellText(0)).isEqualTo("Kind north");
            assertThat(rows.get(3).getCellText(0)).isEqualTo("Total");
            assertThat(new BigDecimal(rows.get(3).getCellText(1))).isEqualByComparingTo("1200");
        }
    }

    @Test
    void theXlsxOfAPivotIsItsMatrixWithTotals() throws Exception {
        MockHttpServletResponse file = north.send(get("/api/v1/entities/" + orders + "/report/export")
                .param("groupBy", PIVOT)
                .param("measures", "[{\"op\":\"sum\",\"field\":\"total\"}]")
                .param("pivot", "true")
                .param("lang", "ru"));
        assertThat(file.getStatus()).as(file.getContentAsString()).isEqualTo(200);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows).hasSize(4);
            assertThat(rows.get(0).getCellText(1)).isEqualTo("2026-08-01");
            assertThat(rows.get(0).getCellText(3)).isEqualTo("Итого");
            assertThat(rows.get(1).getCellText(0)).isEqualTo("Тип east");
            assertThat(rows.get(2).getCellText(0)).isEqualTo("Тип north");
            assertThat(new BigDecimal(rows.get(2).getCellText(1))).isEqualByComparingTo("100");
            assertThat(new BigDecimal(rows.get(2).getCellText(3))).isEqualByComparingTo("180");
            assertThat(rows.get(3).getCellText(0)).isEqualTo("Итого");
            assertThat(new BigDecimal(rows.get(3).getCellText(3))).isEqualByComparingTo("200");
        }
    }

    private Map<String, Object> report(TestSession session, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/api/v1/entities/" + orders + "/report").header("X-Time-Zone", "Asia/Tashkent");
        params.forEach(request::param);
        MockHttpServletResponse answer = session.send(request);
        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(200);
        return TestSession.object(answer);
    }

    private Map<String, Object> saved(TestSession session, long id, String parameters) throws Exception {
        MockHttpServletResponse answer = session.send(get("/api/v1/entities/" + orders + "/reports/" + id)
                .param("parameters", parameters)
                .header("X-Time-Zone", "Asia/Tashkent"));
        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(200);
        return TestSession.object(answer);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> totals(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("totals");
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> cells(Map<String, Object> result) {
        return rows(result).stream()
                .map(row -> (List<Object>) row.get("groups"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> groups(Map<String, Object> result) {
        return rows(result).stream()
                .map(row -> ((List<Object>) row.get("groups")).getFirst())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> labels(Map<String, Object> row) {
        return (List<Object>) row.get("labels");
    }

    @SuppressWarnings("unchecked")
    private static List<Double> values(Map<String, Object> row) {
        return ((List<Object>) row.get("values"))
                .stream()
                        .map(value -> value == null ? null : ((Number) value).doubleValue())
                        .toList();
    }
}
