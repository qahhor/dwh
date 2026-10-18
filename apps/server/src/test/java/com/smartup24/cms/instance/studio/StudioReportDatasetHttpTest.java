package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioDocumentFixtures.column;
import static com.smartup24.cms.instance.studio.StudioFormFixtures.field;
import static com.smartup24.cms.instance.studio.StudioFormFixtures.labels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR-0044 over the server alone: an administrator defines a dataset over the lines of a live order form — the region
 * of the order, the product, quantity and amount of each line, a fixed filter and a measure computed by a formula over
 * aggregates — and readers of two org units report the sums of the lines by product and region, each over the orders
 * of their own unit. A dataset never widens a reader's view: without the form's right it is not offered at all.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioReportDatasetHttpTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private String tag;
    private String orders;
    private String dataset;
    private TestSession admin;
    private TestSession north;
    private TestSession south;
    private TestSession stranger;

    @BeforeEach
    void setup() throws Exception {
        TestUsers users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        long unitA = users.unit("dsa-" + tag);
        long unitB = users.unit("dsb-" + tag);
        TestSession publisher = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        "studio.forms", Set.of("publish"),
                                        "studio.lists", Set.of("view", "manage"),
                                        "md.settings", Set.of("update")),
                                unitA)
                        .login());
        String regions = "dregions_" + tag;
        String products = "dproducts_" + tag;
        forms.list(publisher, regions, List.of("north", "south"));
        forms.list(publisher, products, List.of("tea", "coffee"));
        orders = forms.activate(publisher, order("dorders_" + tag, regions, products));
        Map<String, Set<String>> reading = Map.of(orders, Set.of("view", "create"), "md.profile", Set.of("view"));
        admin = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        orders,
                                        Set.of("view"),
                                        "md.report_datasets",
                                        Set.of("view", "manage"),
                                        "md.profile",
                                        Set.of("view")),
                                unitA)
                        .login());
        north = TestSession.signIn(context, users.withRights(reading, unitA).login());
        south = TestSession.signIn(context, users.withRights(reading, unitB).login());
        stranger = TestSession.signIn(
                context,
                users.withRights(Map.of("md.profile", Set.of("view")), unitA).login());
        create(north, "north", "2026-09-05", List.of(line("tea", "2", "10"), line("coffee", "1", "30")));
        create(north, "south", "2026-09-06", List.of(line("tea", "1", "10"), line("tea", "0", "10")));
        create(south, "north", "2026-09-07", List.of(line("coffee", "5", "30")));
        dataset = "lines_" + tag;
    }

    @Test
    void anAdministratorsDatasetOfLinesReportsEachReaderTheirOwnUnit() throws Exception {
        MockHttpServletResponse fields = admin.send(
                get("/api/v1/report-datasets/fields").param("entity", orders).param("lines", "items"));
        assertThat(fields.getStatus()).as(fields.getContentAsString()).isEqualTo(200);
        assertThat(fields.getContentAsString()).contains("\"lineProduct\"", "\"lineAmount\"", "\"region\"");

        MockHttpServletResponse created = admin.send(post("/api/v1/report-datasets"), definition(), null);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        assertThat(created.getHeader("Location")).endsWith("/api/v1/report-datasets/" + dataset);

        Map<String, Object> catalog =
                TestSession.object(north.send(get("/api/v1/entities/" + orders + "/report-datasets")));
        assertThat(catalog.toString()).contains("ds." + dataset, "Строки заказов", "avgLine");
        assertThat(stranger.send(get("/api/v1/entities/" + orders + "/report-datasets"))
                        .getStatus())
                .isIn(403, 404);

        Map<String, Object> mine = report(
                north,
                Map.of(
                        "groupBy", "[{\"field\":\"lineProduct\"},{\"field\":\"region\"}]",
                        "measures", "[{\"op\":\"sum\",\"field\":\"lineAmount\"},{\"op\":\"count\"}]",
                        "pivot", "true"));
        // The line of quantity 0 is outside the fixed filter; the order of the other unit is not the reader's.
        assertThat(mine.toString()).contains("[coffee, north]", "[tea, north]", "[tea, south]");
        assertThat(rows(mine)).hasSize(3);
        assertThat(values(rows(mine).get(1))).containsExactly(20.0, 1.0);
        assertThat(words(rows(mine).get(1))).first().isEqualTo("Тип tea");

        Map<String, Object> computed = report(
                north,
                Map.of(
                        "groupBy", "[{\"field\":\"region\"}]",
                        "measures", "[{\"op\":\"formula\",\"field\":\"avgLine\"}]",
                        "totals", "true"));
        assertThat(values(rows(computed).get(0))).containsExactly(25.0);
        assertThat(values(totals(computed).getFirst())).containsExactly(20.0);

        Map<String, Object> theirs = report(
                south,
                Map.of(
                        "groupBy", "[{\"field\":\"lineProduct\"}]",
                        "measures", "[{\"op\":\"sum\",\"field\":\"lineAmount\"}]"));
        assertThat(rows(theirs))
                .singleElement()
                .satisfies(row -> assertThat(values(row)).containsExactly(150.0));

        MockHttpServletResponse hidden = north.send(get("/api/v1/entities/" + orders + "/report")
                .param("dataset", "ds." + dataset)
                .param("groupBy", "[{\"field\":\"total\"}]"));
        assertThat(hidden.getStatus()).isEqualTo(422);
        MockHttpServletResponse sorted = north.send(get("/api/v1/entities/" + orders + "/report")
                .param("dataset", "ds." + dataset)
                .param("groupBy", "[{\"field\":\"region\"}]")
                .param("measures", "[{\"op\":\"formula\",\"field\":\"avgLine\"}]")
                .param("sort", "{\"by\":\"measure\",\"measure\":0,\"direction\":\"desc\"}"));
        assertThat(sorted.getStatus()).isEqualTo(422);
        assertThat(sorted.getContentAsString()).contains("sort.measure");

        assertThat(admin.send(delete("/api/v1/report-datasets/" + dataset).header("If-Match", "\"9\""), Map.of(), null)
                        .getStatus())
                .isEqualTo(409);
        assertThat(admin.send(delete("/api/v1/report-datasets/" + dataset).header("If-Match", "\"1\""), Map.of(), null)
                        .getStatus())
                .isEqualTo(204);
        assertThat(north.send(get("/api/v1/entities/" + orders + "/report").param("dataset", "ds." + dataset))
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void aDatasetIsCheckedAtEachPartAndChangedAtItsRevision() throws Exception {
        Map<String, Object> wrong = new LinkedHashMap<>(definition());
        wrong.put("fields", List.of("region", "total", "nothing"));
        wrong.put("measures", List.of(Map.of("key", "bad", "labels", labels("Плохой"), "formula", "sum_nothing /")));
        wrong.put("filter", List.of(Map.of("field", "lineQty", "op", "like", "value", 1)));
        MockHttpServletResponse refused = admin.send(post("/api/v1/report-datasets"), wrong, null);
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString())
                .contains("fields[1]", "error.md.report_dataset_field_grain", "fields[2]", "measures[0].formula")
                .contains("filter[0]");
        Map<String, Object> lines = new LinkedHashMap<>(definition());
        lines.put("lines", "nothing");
        assertThat(admin.send(post("/api/v1/report-datasets"), lines, null).getContentAsString())
                .contains("\"lines\"");
        assertThat(stranger.send(post("/api/v1/report-datasets"), definition(), null)
                        .getStatus())
                .isEqualTo(403);

        assertThat(admin.send(post("/api/v1/report-datasets"), definition(), null)
                        .getStatus())
                .isEqualTo(201);
        assertThat(admin.send(post("/api/v1/report-datasets"), definition(), null)
                        .getStatus())
                .isEqualTo(409);
        Map<String, Object> renamed = new LinkedHashMap<>(definition());
        renamed.put("name", labels("Строки (новые)"));
        assertThat(admin.send(put("/api/v1/report-datasets/" + dataset), renamed, null)
                        .getStatus())
                .isEqualTo(428);
        MockHttpServletResponse changed =
                admin.send(put("/api/v1/report-datasets/" + dataset).header("If-Match", "\"1\""), renamed, null);
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(changed.getHeader("ETag")).isEqualTo("\"2\"");
        Map<String, Object> listed = TestSession.object(admin.send(get("/api/v1/report-datasets")));
        assertThat(listed.toString()).contains(dataset, "Строки (новые)");
        assertThat(admin.send(get("/api/v1/report-datasets/" + dataset)).getHeader("ETag"))
                .isEqualTo("\"2\"");
    }

    private Map<String, Object> definition() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", dataset);
        body.put("name", labels("Строки заказов"));
        body.put("entity", orders);
        body.put("lines", "items");
        body.put("fields", List.of("region", "orderdate", "lineProduct", "lineQty", "lineAmount"));
        body.put("filter", List.of(Map.of("field", "lineQty", "op", "gt", "value", 0)));
        body.put(
                "measures",
                List.of(Map.of(
                        "key", "avgLine", "labels", labels("Средняя строка"), "formula", "sum_lineAmount / count")));
        return body;
    }

    private void create(TestSession writer, String region, String date, List<Map<String, Object>> lines)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("region", region);
        body.put("orderdate", date);
        body.put("items", lines);
        forms.create(writer, orders, body);
    }

    private static Map<String, Object> line(String product, String qty, String price) {
        return Map.of("product", product, "qty", qty, "price", price);
    }

    /** An order of format 6: a region, a date, a total of its lines; lines of a product, a quantity, a price, a sum. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> order(String name, String regions, String products) {
        Map<String, Object> declaration = forms.plain(
                name,
                "Заказы",
                "org_unit",
                List.of(
                        field("region", "ref", true, Map.of("target", Map.of("kind", "list", "code", regions))),
                        field("orderdate", "date", true, Map.of()),
                        field("total", "number", false, Map.of("scale", 2, "formula", "sum(lines.items.amount)"))));
        declaration.put("formatVersion", 6);
        Map<String, Object> entity = new LinkedHashMap<>((Map<String, Object>) declaration.get("entity"));
        declaration.put("entity", entity);
        declaration.put(
                "lines",
                List.of(Map.of(
                        "key",
                        "items",
                        "labels",
                        labels("Строки"),
                        "maxRows",
                        50,
                        "fields",
                        List.of(
                                column(
                                        "product",
                                        "ref",
                                        true,
                                        Map.of("target", Map.of("kind", "list", "code", products))),
                                column("qty", "number", true, Map.of("scale", 3)),
                                column("price", "number", true, Map.of("scale", 2)),
                                column("amount", "number", false, Map.of("scale", 2, "formula", "qty * price"))))));
        return declaration;
    }

    private Map<String, Object> report(TestSession session, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/api/v1/entities/" + orders + "/report").param("dataset", "ds." + dataset);
        params.forEach(request::param);
        MockHttpServletResponse answer = session.send(request);
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
    private static List<Object> words(Map<String, Object> row) {
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
