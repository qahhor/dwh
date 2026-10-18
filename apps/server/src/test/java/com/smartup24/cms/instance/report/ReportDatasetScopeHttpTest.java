package com.smartup24.cms.instance.report;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
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
import java.util.Arrays;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/** Current ACL and both scopes hold for direct, saved, widget and workbook execution (ADR-0039). */
@Import(ReportDatasetScopeHttpTest.Fixture.class)
class ReportDatasetScopeHttpTest extends EmbeddedPostgresTest {
    private static final String ROOT = "example.datasetrequests";
    private static final String TARGET = "example.datasetproducts";
    private static final String DATASET = "example.scopedproducts";
    private static final String PATH = "/api/v1/entities/" + ROOT;
    private static final Map<String, Set<String>> RIGHTS = Map.of(
            "example.requests",
            Set.of("view"),
            "example.products",
            Set.of("view"),
            "md.modules",
            Set.of("view"),
            "md.settings",
            Set.of("update"),
            "md.profile",
            Set.of("view", "update"));

    @TestConfiguration
    static class Fixture {
        @Bean
        EntityDefinition datasetRequestFixture() {
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
                            .target(TARGET, "name")
                            .requires("md.settings", "update"))
                    .field(number("qty", "example.requests.col.qty")
                            .column("qty")
                            .required())
                    .section("main", "entity.section.main", "subject", "productId", "qty")
                    .defaultSort("subject", Entity.Sort.ASC)
                    .build();
        }

        @Bean
        EntityDefinition datasetProductFixture() {
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
                    .field(select(
                                    "unit",
                                    "example.products.col.unit",
                                    List.of("pcs", "kg", "l"),
                                    "example.products.unit.")
                            .column("unit")
                            .requires("md.modules", "view"))
                    .section("main", "entity.section.main", "code", "name", "unit")
                    .defaultSort("name", Entity.Sort.ASC)
                    .archivable()
                    .build();
        }

        @Bean
        EntityReportDataset scopedReportDataset() {
            return new EntityReportDataset(
                    DATASET,
                    ROOT,
                    "example.requests.dataset.products",
                    "productId",
                    List.of(new EntityReportDataset.Projection("productUnit", "unit")));
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

    private TestUser reader;
    private TestSession session;
    private TestUsers users;
    private String tag;

    @BeforeEach
    void setup() throws Exception {
        modules.toggleModuleStatus("example", true);
        users = TestUsers.of(context);
        tag = UUID.randomUUID().toString();
        reader = users.withRights(RIGHTS, users.unit("dataset-scope-" + tag));
        session = TestSession.signIn(context, reader.login());
    }

    @Test
    void targetOwnerAndArchiveAreFilteredInsideLeftJoinWhileRootScopeStillApplies() throws Exception {
        var other = users.withRights(RIGHTS, users.unit("dataset-other-" + tag));
        long own = product(reader, "kg");
        long hidden = product(other, "l");
        long archived = product(reader, "pcs");
        request(reader, own, 2);
        request(reader, own, 3);
        request(reader, hidden, 7);
        request(reader, archived, 11);
        request(other, own, 1000);
        jdbc.sql("update ex_products set archived_at = now(), archived_by = created_by where id = :id")
                .param("id", archived)
                .update();
        var result = session.send(report("/report"));
        status(result, 200);
        assertThat(ReportRows.values(TestSession.object(result)))
                .isEqualTo(List.of(
                        Map.of("groups", List.of("kg"), "values", List.of(2, 5.0)),
                        Map.of("groups", Arrays.asList((Object) null), "values", List.of(2, 18.0))));
        var export = session.send(report("/report/export"));
        status(export, 200);
        var cells = AggregateXlsxCells.data(export.getContentAsByteArray());
        // The unit is written by its label in the export language, not by its code.
        assertThat(cells.get("A2")).isNotNull().isNotEqualTo(new AggregateXlsxCells.Cell("text", "kg"));
        assertThat(cells.get("A2").type()).isEqualTo("text");
        assertThat(cells)
                .containsEntry("B2", new AggregateXlsxCells.Cell("number", "2"))
                .containsEntry("C2", new AggregateXlsxCells.Cell("number", "5.000"))
                .containsEntry("B3", new AggregateXlsxCells.Cell("number", "2"))
                .containsEntry("C3", new AggregateXlsxCells.Cell("number", "18.000"));
        assertThat(cells).doesNotContainKey("A3");
    }

    @Test
    void fieldRevocationRemovesMetadataAndEverySavedConsumerForSameSession() throws Exception {
        request(reader, product(reader, "kg"), 3);
        long view = save();
        var board = session.send(
                post("/api/v1/dashboards"),
                Map.of("name", tag, "widgets", List.of(Map.of("viewId", view, "width", 1))),
                null);
        status(board, 201);
        var detail = TestSession.object(board);
        String widget = "/api/v1/dashboards/" + detail.get("id") + "/widgets/" + view;
        status(session.send(get(widget + "/result").header("If-Match", detail.get("revision"))), 200);
        status(session.send(get(widget + "/export").header("If-Match", detail.get("revision"))), 200);
        revoke("md.modules", "view");
        var metadata = session.send(get(PATH + "/report-meta").param("dataset", DATASET));
        status(metadata, 200);
        assertThat(metadata.getContentAsString()).doesNotContain("productUnit");
        status(session.send(report("/report")), 422);
        status(session.send(report("/report/export")), 422);
        status(session.send(get(PATH + "/reports/" + view)), 404);
        status(session.send(get(PATH + "/reports/" + view + "/export")), 404);
        assertThat(TestSession.object(session.send(get("/api/v1/report-views"))).get("items"))
                .isEqualTo(List.of());
        assertThat(TestSession.array(session.send(get("/api/v1/report-widgets"))))
                .isEmpty();
        status(session.send(get(widget + "/result").header("If-Match", detail.get("revision"))), 404);
        status(session.send(get(widget + "/export").header("If-Match", detail.get("revision"))), 404);
        var refusedSave = session.send(post("/api/v1/list-views/" + ROOT), definition(), null);
        status(refusedSave, 422);
    }

    @Test
    void targetAndReferenceRevocationAndModuleOffCloseDatasetWithoutFallback() throws Exception {
        long view = save();
        revoke("example.products", "view");
        unavailable(view);
    }

    @Test
    void referenceFieldRightIsRequiredBeforeDatasetMetadataCanBeRead() throws Exception {
        long view = save();
        revoke("md.settings", "update");
        unavailable(view);
    }

    @Test
    void disabledModuleClosesExistingSavedDataset() throws Exception {
        long view = save();
        modules.toggleModuleStatus("example", false);
        try {
            status(session.send(get(PATH + "/report-datasets")), 404);
            unavailable(view);
        } finally {
            modules.toggleModuleStatus("example", true);
        }
    }

    private void unavailable(long view) throws Exception {
        status(session.send(get(PATH + "/report-meta").param("dataset", DATASET)), 404);
        status(session.send(report("/report")), 404);
        status(session.send(report("/report/export")), 404);
        status(session.send(get(PATH + "/reports/" + view)), 404);
        status(session.send(get(PATH + "/reports/" + view + "/export")), 404);
        assertThat(TestSession.object(session.send(get("/api/v1/report-views"))).get("items"))
                .isEqualTo(List.of());
    }

    private long save() throws Exception {
        var response = session.send(post("/api/v1/list-views/" + ROOT), definition(), null);
        status(response, 201);
        return ((Number) TestSession.object(response).get("id")).longValue();
    }

    private Map<String, Object> definition() {
        return Map.of(
                "name",
                tag,
                "kind",
                "widget",
                "state",
                Map.of(
                        "dataset",
                        DATASET,
                        "groupBy",
                        List.of(Map.of("field", "productUnit")),
                        "chart",
                        "horizontalBar"));
    }

    private void revoke(String form, String action) {
        jdbc.sql("delete from md_role_permissions where form_code = :form and action = :action "
                        + "and role_id in (select role_id from md_user_roles where user_id = :user)")
                .param("form", form)
                .param("action", action)
                .param("user", reader.id())
                .update();
        permissions.recalculateEffectivePermissions(reader.id());
    }

    private MockHttpServletRequestBuilder report(String suffix) {
        return get(PATH + suffix)
                .param("dataset", DATASET)
                .param("groupBy", "[{\"field\":\"productUnit\"}]")
                .param("measures", "[{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"qty\"}]")
                .param("filter", "[{\"field\":\"subject\",\"op\":\"eq\",\"value\":\"" + tag + "\"}]");
    }

    private long product(TestUser owner, String unit) {
        return jdbc.sql("insert into ex_products(code,name,unit,created_by,modified_by) "
                        + "values(:code,:name,:unit,:owner,:owner) returning id")
                .param("code", UUID.randomUUID().toString())
                .param("name", tag)
                .param("unit", unit)
                .param("owner", owner.id())
                .query(Long.class)
                .single();
    }

    private void request(TestUser owner, long product, int qty) {
        jdbc.sql("insert into ex_requests(subject,product_id,qty,org_unit_id,created_by,modified_by) "
                        + "values(:subject,:product,:qty,:unit,:owner,:owner)")
                .param("subject", tag)
                .param("product", product)
                .param("qty", qty)
                .param("unit", owner.unit())
                .param("owner", owner.id())
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
