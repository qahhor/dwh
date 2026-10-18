package com.smartup24.cms.instance.report;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.entity.report.EntityReportDataset;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/** The dashboard period resolves the same saved dataset and current ACL as its result and workbook (ADR-0039). */
@Import(ReportDatasetPeriodHttpTest.Fixture.class)
class ReportDatasetPeriodHttpTest extends EmbeddedPostgresTest {
    private static final String ROOT = "example.periodrequests";
    private static final String TARGET = "example.periodproducts";
    private static final String DATASET = "example.periodrequests.products";

    @TestConfiguration
    static class Fixture {
        @Bean
        EntityDefinition periodRequestFixture() {
            return Entity.define(ROOT, "example.requests")
                    .table("ex_requests", "p")
                    .scope(EntityScope.orgUnit("org_unit_id", "created_by"))
                    .field(text("subject", "example.requests.col.subject")
                            .column("subject")
                            .required()
                            .list(sortable()))
                    .field(ref("productId", "example.requests.col.product_id")
                            .column("product_id")
                            .required()
                            .target(TARGET, "name"))
                    .field(number("qty", "example.requests.col.qty")
                            .column("qty")
                            .required())
                    .section("main", "entity.section.main", "subject", "productId", "qty")
                    .defaultSort("subject", Entity.Sort.ASC)
                    .build();
        }

        @Bean
        EntityDefinition periodProductFixture() {
            return Entity.define(TARGET, "example.products")
                    .table("ex_products", "p")
                    .scope(EntityScope.owner("created_by"))
                    .field(text("code", "example.products.col.code")
                            .column("code")
                            .required())
                    .field(text("name", "example.products.col.name")
                            .column("name")
                            .required()
                            .list(sortable()))
                    .field(instant("createdAt", "projects.col.created_at")
                            .system(SystemColumn.CREATED_AT)
                            .requires("md.modules", "view"))
                    .section("main", "entity.section.main", "code", "name")
                    .defaultSort("name", Entity.Sort.ASC)
                    .archivable()
                    .build();
        }

        @Bean
        EntityReportDataset periodReportDataset() {
            return new EntityReportDataset(
                    DATASET,
                    ROOT,
                    "example.requests.dataset.products",
                    "productId",
                    List.of(new EntityReportDataset.Projection("productCreatedAt", "createdAt")));
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModuleRegistryService modules;

    @Autowired
    private MdPermissionService permissions;

    private TestSession session;
    private TestUser reader;
    private String tag;

    @BeforeEach
    void setup() throws Exception {
        modules.toggleModuleStatus("example", true);
        tag = UUID.randomUUID().toString();
        var users = TestUsers.of(context);
        reader = users.withRights(
                Map.of(
                        "example.requests",
                        Set.of("view"),
                        "example.products",
                        Set.of("view"),
                        "md.modules",
                        Set.of("view"),
                        "md.profile",
                        Set.of("view", "update")),
                users.unit("dataset-period-" + tag));
        session = TestSession.signIn(context, reader.login());
    }

    @Test
    void projectedInstantPeriodFiltersJsonAndWorkbookWithSameUtcBounds() throws Exception {
        row("2026-09-30T23:59:59Z", 1);
        row("2026-10-01T00:00:00Z", 2);
        row("2026-10-01T23:59:59Z", 3);
        row("2026-10-02T00:00:00Z", 4);
        long view = save();
        var board = createBoard(view);
        String widget = "/api/v1/dashboards/" + board.get("id") + "/widgets/" + view;
        var result = session.send(get(widget + "/result")
                .header("If-Match", board.get("revision"))
                .param("from", "2026-10-01")
                .param("to", "2026-10-01"));
        status(result, 200);
        assertThat(ReportRows.values(TestSession.object(result)))
                .isEqualTo(List.of(Map.of("groups", List.of(), "values", List.of(2, 5.0))));
        var export = session.send(get(widget + "/export")
                .header("If-Match", board.get("revision"))
                .param("from", "2026-10-01")
                .param("to", "2026-10-01"));
        status(export, 200);
        assertThat(AggregateXlsxCells.data(export.getContentAsByteArray()))
                .containsEntry("A2", new AggregateXlsxCells.Cell("number", "2"))
                .containsEntry("B2", new AggregateXlsxCells.Cell("number", "5.000"))
                .doesNotContainKey("A3");
        var unfiltered = session.send(get(widget + "/result").header("If-Match", board.get("revision")));
        status(unfiltered, 200);
        assertThat(ReportRows.values(TestSession.object(unfiltered)))
                .isEqualTo(List.of(Map.of("groups", List.of(), "values", List.of(4, 10.0))));
    }

    @Test
    void revokingOnlyPeriodProjectionClosesExistingPanelButKeepsValidSavedReport() throws Exception {
        row("2026-10-01T12:00:00Z", 5);
        long view = save();
        var board = createBoard(view);
        String base = "/api/v1/dashboards/" + board.get("id");
        jdbc.sql("delete from md_role_permissions where form_code = 'md.modules' and action = 'view' "
                        + "and role_id in (select role_id from md_user_roles where user_id = :user)")
                .param("user", reader.id())
                .update();
        permissions.recalculateEffectivePermissions(reader.id());
        status(session.send(get("/api/v1/entities/" + ROOT + "/reports/" + view)), 200);
        var detail = session.send(get(base));
        status(detail, 200);
        assertThat(TestSession.object(detail).get("widgets"))
                .isEqualTo(List.of(Map.of("viewId", Math.toIntExact(view), "width", 1, "available", false)));
        String widget = base + "/widgets/" + view;
        for (String suffix : List.of("/result", "/export")) {
            status(
                    session.send(get(widget + suffix)
                            .header("If-Match", board.get("revision"))
                            .param("from", "2026-10-01")
                            .param("to", "2026-10-01")),
                    404);
        }
    }

    private long save() throws Exception {
        var result = session.send(
                post("/api/v1/list-views/" + ROOT),
                Map.of(
                        "name",
                        tag,
                        "kind",
                        "widget",
                        "state",
                        Map.of(
                                "dataset",
                                DATASET,
                                "chart",
                                "table",
                                "groupBy",
                                List.of(),
                                "measures",
                                List.of(Map.of("op", "count"), Map.of("op", "sum", "field", "qty")),
                                "filter",
                                List.of(Map.of("field", "subject", "op", "eq", "value", tag)))),
                null);
        status(result, 201);
        return ((Number) TestSession.object(result).get("id")).longValue();
    }

    private Map<String, Object> createBoard(long view) throws Exception {
        var result = session.send(
                post("/api/v1/dashboards"),
                Map.of(
                        "name",
                        tag,
                        "widgets",
                        List.of(Map.of("viewId", view, "periodField", "productCreatedAt", "width", 1))),
                null);
        status(result, 201);
        return TestSession.object(result);
    }

    private void row(String createdAt, int qty) {
        long product = jdbc.sql("insert into ex_products(code,name,created_by,modified_by,created_at) "
                        + "values(:code,:name,:owner,:owner,cast(:createdAt as timestamptz)) returning id")
                .param("code", UUID.randomUUID().toString())
                .param("name", tag)
                .param("owner", reader.id())
                .param("createdAt", createdAt)
                .query(Long.class)
                .single();
        jdbc.sql("insert into ex_requests(subject,product_id,qty,org_unit_id,created_by,modified_by) "
                        + "values(:subject,:product,:qty,:unit,:owner,:owner)")
                .param("subject", tag)
                .param("product", product)
                .param("qty", qty)
                .param("unit", reader.unit())
                .param("owner", reader.id())
                .update();
    }

    private static void status(MockHttpServletResponse response, int expected) throws Exception {
        assertThat(response.getStatus())
                .as(
                        response.getContentType() != null
                                        && response.getContentType().contains("json")
                                ? response.getContentAsString()
                                : "HTTP status")
                .isEqualTo(expected);
    }
}
