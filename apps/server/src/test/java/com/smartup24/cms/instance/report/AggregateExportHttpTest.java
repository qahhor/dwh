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
import org.springframework.web.context.WebApplicationContext;

/** Aggregate downloads use the same scope and source permissions as their results (ADR-0036). */
class AggregateExportHttpTest extends EmbeddedPostgresTest {
    private static final String ENTITY = "/api/v1/entities/example.orders";
    private static final Map<String, Set<String>> RIGHTS =
            Map.of("example.orders", Set.of("view", "create"), "md.profile", Set.of("view", "update"));

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private ModuleRegistryService modules;

    private TestSession owner;
    private TestUsers users;
    private String tag;

    @BeforeEach
    void setup() throws Exception {
        modules.toggleModuleStatus("example", true);
        users = TestUsers.of(wac);
        tag = UUID.randomUUID().toString().substring(0, 8);
        owner = TestSession.signIn(
                wac, users.withRights(RIGHTS, users.unit("export-" + tag)).login());
    }

    @Test
    void exportsAggregatesOnlyWithinReaderScope() throws Exception {
        order(owner, "3.00");
        var outsider = TestSession.signIn(
                wac, users.withRights(RIGHTS, users.unit("other-" + tag)).login());
        order(outsider, "777.00");
        var response = owner.send(get(ENTITY + "/report/export")
                .param("groupBy", "[{\"field\":\"status\"}]")
                .param("measures", "[{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"total\"}]")
                .param("filter", filter())
                .param("lang", "en"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains("spreadsheetml.sheet");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        String xml = AggregateXlsxCells.xml(response.getContentAsByteArray());
        // The status is written by its label in the export language, the currency as it is.
        assertThat(xml).contains("Draft", "UZS", "<v>6.00</v>").doesNotContain("777", tag);
    }

    @Test
    void savedExportRequiresSourceAccessAndRejectsUnknownFields() throws Exception {
        var created = owner.send(
                post("/api/v1/list-views/example.orders"),
                Map.of(
                        "name",
                        "Aggregate " + tag,
                        "kind",
                        "report",
                        "state",
                        Map.of(
                                "groupBy",
                                List.of(Map.of("field", "status")),
                                "measures",
                                List.of(Map.of("op", "count")),
                                "chart",
                                "bar")),
                null);
        assertThat(created.getStatus()).isEqualTo(201);
        long id = ((Number) TestSession.object(created).get("id")).longValue();
        assertThat(owner.send(get(ENTITY + "/reports/" + id + "/export")).getStatus())
                .isEqualTo(200);
        var other = TestSession.signIn(
                wac, users.withRights(RIGHTS, users.unit("reader-" + tag)).login());
        assertThat(other.send(get(ENTITY + "/reports/" + id + "/export")).getStatus())
                .isEqualTo(404);
        assertThat(owner.send(get(ENTITY + "/report/export").param("groupBy", "[{\"field\":\"secret\"}]"))
                        .getStatus())
                .isEqualTo(422);
    }

    private void order(TestSession session, String price) throws Exception {
        assertThat(session.send(
                                post(ENTITY),
                                Map.of(
                                        "customer",
                                        "Customer " + tag,
                                        "currency",
                                        "UZS",
                                        "lines",
                                        List.of(Map.of("product", "Flour", "qty", "2", "price", price))))
                        .getStatus())
                .isEqualTo(201);
    }

    private String filter() {
        return "[{\"field\":\"customer\",\"op\":\"contains\",\"value\":\"" + tag + "\"}]";
    }
}
