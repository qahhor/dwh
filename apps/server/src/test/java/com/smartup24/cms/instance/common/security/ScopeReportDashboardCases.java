package com.smartup24.cms.instance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Executed record-access cases for role-shared preferences and freshly authorized downloads (ADR-0036). */
final class ScopeReportDashboardCases {
    private static final String CODE = "example.orders";
    private static final String VIEWS = "/api/v1/list-views/" + CODE;
    private static final String BOARDS = "/api/v1/dashboards";

    record Case(String handler, HttpMethod method, String path, int allowed) {}

    static final List<Case> CASES = List.of(
            new Case(
                    "EntityReportController#datasets",
                    HttpMethod.GET,
                    "/api/v1/entities/" + CODE + "/report-datasets",
                    200),
            new Case(
                    "EntityReportController#metadata",
                    HttpMethod.GET,
                    "/api/v1/entities/" + CODE + "/report-meta",
                    200),
            new Case("MdReportViewController#sharing", HttpMethod.GET, VIEWS + "/{view}/sharing", 200),
            new Case("MdReportViewController#share", HttpMethod.PUT, VIEWS + "/{view}/sharing", 200),
            new Case("MdDashboardController#get", HttpMethod.GET, BOARDS + "/{board}", 200),
            new Case("MdDashboardController#update", HttpMethod.PUT, BOARDS + "/{board}", 200),
            new Case("MdDashboardController#delete", HttpMethod.DELETE, BOARDS + "/{board}", 204),
            new Case("MdDashboardController#sharing", HttpMethod.GET, BOARDS + "/{board}/sharing", 200),
            new Case("MdDashboardController#share", HttpMethod.PUT, BOARDS + "/{board}/sharing", 200),
            new Case("MdDashboardController#result", HttpMethod.GET, BOARDS + "/{board}/widgets/{view}/result", 200),
            new Case(
                    "AggregateExportController#report",
                    HttpMethod.GET,
                    "/api/v1/entities/" + CODE + "/report/export",
                    200),
            new Case(
                    "AggregateExportController#saved",
                    HttpMethod.GET,
                    "/api/v1/entities/" + CODE + "/reports/{view}/export",
                    200),
            new Case(
                    "AggregateExportController#dashboard",
                    HttpMethod.GET,
                    BOARDS + "/{board}/widgets/{view}/export",
                    200));

    private final ModuleRegistryService modules;
    private final TransactionTemplate transactions;
    private final TestSession owner;
    private final TestSession other;
    private final TestSession withoutEntity;

    ScopeReportDashboardCases(WebApplicationContext context) throws Exception {
        modules = context.getBean(ModuleRegistryService.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var users = TestUsers.of(context);
        var rights = Map.of(
                CODE, List.of("view"), "md.profile", List.of("view", "update"), "md.settings", List.of("update"));
        owner = TestSession.signIn(
                context, users.withRights(rights, users.unit("report-author")).login());
        other = TestSession.signIn(
                context, users.withRights(rights, users.unit("report-other")).login());
        withoutEntity = TestSession.signIn(
                context, users.withRights(Map.of(), users.unit("report-denied")).login());
    }

    void verify(Case c) {
        transactions.executeWithoutResult(transaction -> {
            boolean enabled = modules.isModuleActive("example");
            try {
                modules.toggleModuleStatus("example", true);
                exercise(c);
            } catch (Exception failure) {
                throw new AssertionError("Report access case failed: " + c.handler(), failure);
            } finally {
                try {
                    modules.toggleModuleStatus("example", enabled);
                } finally {
                    transaction.setRollbackOnly();
                }
            }
        });
    }

    private void exercise(Case c) throws Exception {
        var created = owner.send(
                request(HttpMethod.POST, VIEWS),
                Map.of(
                        "name",
                        "Scope report",
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
        status(created, 201);
        long view = ((Number) TestSession.object(created).get("id")).longValue();
        var body = Map.of(
                "name",
                "Scope panel",
                "widgets",
                List.of(Map.of("viewId", view, "width", 1, "periodField", "orderDate")));
        var made = owner.send(request(HttpMethod.POST, BOARDS), body, null);
        status(made, 201);
        var board = TestSession.object(made);
        String path = c.path()
                .replace("{view}", Long.toString(view))
                .replace("{board}", board.get("id").toString());
        String revision =
                c.path().startsWith(VIEWS) ? "0" : board.get("revision").toString();
        Object payload = path.endsWith("/sharing") ? Map.of("roleIds", List.of()) : body;
        TestSession denied = (c.handler().equals("AggregateExportController#report")
                        || c.handler().startsWith("EntityReportController#"))
                ? withoutEntity
                : other;
        status(send(denied, c.method(), path, revision, payload), 404);
        status(send(owner, c.method(), path, revision, payload), c.allowed());
    }

    private static MockHttpServletResponse send(
            TestSession session, HttpMethod method, String path, String revision, Object body) throws Exception {
        var builder = request(method, path).header("If-Match", revision);
        return session.send(
                builder, method.equals(HttpMethod.GET) || method.equals(HttpMethod.DELETE) ? null : body, null);
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
