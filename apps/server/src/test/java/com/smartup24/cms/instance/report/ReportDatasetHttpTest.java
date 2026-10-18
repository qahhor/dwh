package com.smartup24.cms.instance.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/** Dataset reports preserve root grain and the same metadata through saved execution and XLSX (ADR-0039). */
@Import(ExampleReportDatasetFixture.class)
class ReportDatasetHttpTest extends EmbeddedPostgresTest {
    private static final String CODE = "example.requests";
    private static final String DATASET = "example.requests.products";
    private static final String PATH = "/api/v1/entities/" + CODE;
    private static final String GROUPS = "[{\"field\":\"productUnit\"}]";
    private static final String MEASURES = "[{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"qty\"}]";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModuleRegistryService modules;

    private TestSession session;
    private String tag;

    @BeforeEach
    void setup() throws Exception {
        modules.toggleModuleStatus("example", true);
        tag = UUID.randomUUID().toString();
        var users = TestUsers.of(context);
        var user = users.withRights(
                Map.of(
                        CODE,
                        Set.of("view", "create"),
                        "example.products",
                        Set.of("view", "create"),
                        "md.profile",
                        Set.of("view", "update")),
                users.unit("dataset-" + tag));
        session = TestSession.signIn(context, user.login());
    }

    @Test
    void metadataIsAnAuthorizedProjectionAndUnknownDatasetDoesNotFallBack() throws Exception {
        var catalog = session.send(get(PATH + "/report-datasets"));
        status(catalog, 200);
        assertThat(TestSession.object(catalog))
                .isEqualTo(Map.of(
                        "items",
                        List.of(Map.of(
                                "code",
                                DATASET,
                                "titleKey",
                                "example.requests.col.product_id",
                                "measures",
                                List.of()))));
        var meta = session.send(get(PATH + "/report-meta").param("dataset", DATASET));
        status(meta, 200);
        assertThat(meta.getContentAsString()).contains("productUnit", "pcs", "nullable\":true");
        status(session.send(get(PATH + "/report-meta").param("dataset", "unknown;select")), 404);
        status(session.send(get(PATH + "/report").param("dataset", "unknown;select")), 404);
        status(session.send(get(PATH + "/report/export").param("dataset", "unknown;select")), 404);
    }

    @Test
    void rootTotalsAndSavedExportRetainTargetProjection() throws Exception {
        long product = product("kg");
        request(product, "2");
        request(product, "3");
        var result = session.send(get(PATH + "/report")
                .param("dataset", DATASET)
                .param("groupBy", GROUPS)
                .param("measures", MEASURES)
                .param("filter", filter()));
        status(result, 200);
        assertThat(ReportRows.values(TestSession.object(result)))
                .isEqualTo(List.of(Map.of("groups", List.of("kg"), "values", List.of(2, 5.0))));
        // The unit is named by its label in the reader's language, not by its code.
        assertThat(ReportRows.labels(TestSession.object(result)))
                .singleElement()
                .satisfies(labels ->
                        assertThat((List<?>) labels).singleElement().isNotNull().isNotEqualTo("kg"));
        var saved = session.send(
                post("/api/v1/list-views/" + CODE),
                Map.of(
                        "name",
                        "Dataset " + tag,
                        "kind",
                        "widget",
                        "state",
                        Map.of(
                                "dataset",
                                DATASET,
                                "groupBy",
                                List.of(Map.of("field", "productUnit")),
                                "measures",
                                List.of(Map.of("op", "count"), Map.of("op", "sum", "field", "qty")),
                                "filter",
                                List.of(Map.of("field", "subject", "op", "eq", "value", tag)),
                                "chart",
                                "table")),
                null);
        status(saved, 201);
        long id = ((Number) TestSession.object(saved).get("id")).longValue();
        var repeated = session.send(get(PATH + "/reports/" + id));
        status(repeated, 200);
        assertThat(TestSession.object(repeated)).isEqualTo(TestSession.object(result));
        var export = session.send(get(PATH + "/reports/" + id + "/export"));
        status(export, 200);
        var cells = AggregateXlsxCells.data(export.getContentAsByteArray());
        assertThat(cells)
                .containsEntry("B2", new AggregateXlsxCells.Cell("number", "2"))
                .containsEntry("C2", new AggregateXlsxCells.Cell("number", "5.000"));
        assertThat(cells).containsEntry("A1", new AggregateXlsxCells.Cell("text", "Единица"));
    }

    @Test
    void archivedTargetBecomesNullWithoutDroppingRootRecords() throws Exception {
        long product = product("l");
        request(product, "7");
        jdbc.sql("update ex_products set archived_at = now(), archived_by = created_by where id = :id")
                .param("id", product)
                .update();
        var result = session.send(get(PATH + "/report")
                .param("dataset", DATASET)
                .param("groupBy", GROUPS)
                .param("measures", MEASURES)
                .param("filter", filter()));
        status(result, 200);
        assertThat(result.getContentAsString()).contains("\"groups\":[null]", "\"values\":[1,7.000]");
    }

    @Test
    void newChartKindsPersistOnlyTheirCompatibleGroupShapes() throws Exception {
        saveChart("horizontalBar", "status", 201);
        saveChart("line", "modifiedAt", 201);
        saveChart("line", "status", 422);
        saveChart("horizontalBar", "modifiedAt", 422);
    }

    private void saveChart(String chart, String field, int expected) throws Exception {
        var response = session.send(
                post("/api/v1/list-views/" + CODE),
                Map.of(
                        "name",
                        chart + field + tag,
                        "kind",
                        "report",
                        "state",
                        Map.of(
                                "chart",
                                chart,
                                "groupBy",
                                List.of(Map.of("field", field)),
                                "measures",
                                List.of(Map.of("op", "count")))),
                null);
        status(response, expected);
    }

    private long product(String unit) throws Exception {
        var result =
                session.send(post("/api/v1/entities/example.products"), Map.of("code", tag, "name", tag, "unit", unit));
        status(result, 201);
        return ((Number) TestSession.object(result).get("id")).longValue();
    }

    private void request(long product, String qty) throws Exception {
        status(session.send(post(PATH), Map.of("subject", tag, "productId", product, "qty", qty)), 201);
    }

    private String filter() {
        return "[{\"field\":\"subject\",\"op\":\"eq\",\"value\":\"" + tag + "\"}]";
    }

    private static void status(MockHttpServletResponse response, int code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(code);
    }
}
