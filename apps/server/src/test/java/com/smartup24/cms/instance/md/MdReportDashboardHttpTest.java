package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.example.service.ExampleOrderEntity;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/** Shared preferences retain the reader's authorization and the owner's revisions (ADR-0036). */
class MdReportDashboardHttpTest extends EmbeddedPostgresTest {
    private static final String ENTITY = ExampleOrderEntity.CODE;
    private static final String VIEWS = "/api/v1/list-views/" + ENTITY;
    private static final String BOARDS = "/api/v1/dashboards";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModuleRegistryService modules;

    private TestUsers users;
    private TestUser owner;
    private TestUser reader;
    private TestSession author;
    private TestSession viewer;
    private String tag;

    @BeforeEach
    void users() throws Exception {
        modules.toggleModuleStatus("example", true);
        users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        owner = users.withRights(
                Map.of(ENTITY, Set.of("view"), "md.profile", Set.of("view", "update"), "md.settings", Set.of("update")),
                users.unit("owner-" + tag));
        reader = users.withRights(
                Map.of(ENTITY, Set.of("view"), "md.profile", Set.of("view", "update")), users.unit("reader-" + tag));
        author = TestSession.signIn(context, owner.login());
        viewer = TestSession.signIn(context, reader.login());
    }

    @Test
    void listViewMutationsRequireTheObservedRevision() throws Exception {
        long id = report("Revisions");
        assertStatus(send(author, put(VIEWS + "/" + id), reportBody("Changed")), 428);
        assertStatus(send(author, delete(VIEWS + "/" + id), null), 428);
        var changed = send(author, put(VIEWS + "/" + id).header("If-Match", "\"0\""), reportBody("Changed"));
        assertStatus(changed, 200);
        assertThat(changed.getHeader("ETag")).isEqualTo("\"1\"");
        assertStatus(send(author, delete(VIEWS + "/" + id).header("If-Match", "\"0\""), null), 409);
        assertStatus(send(author, delete(VIEWS + "/" + id).header("If-Match", "\"1\""), null), 204);
    }

    @Test
    void sharedCatalogAndExecutionReauthorizeTheRoleOnEveryRequest() throws Exception {
        long id = report("Shared");
        String path = "/api/v1/entities/" + ENTITY + "/reports/" + id;
        assertStatus(send(viewer, get(path), null), 404);
        share(VIEWS + "/" + id, 0, role(reader));
        var catalog = send(viewer, get("/api/v1/report-views").param("listCode", ENTITY), null);
        assertStatus(catalog, 200);
        assertThat(catalog.getContentAsString()).contains("\"canEdit\":false", "\"shared\":true", "Shared " + tag);
        assertStatus(send(viewer, get(path), null), 200);
        jdbc.sql("update md_roles set state = 'P' where id = :id")
                .param("id", role(reader))
                .update();
        assertStatus(send(viewer, get(path), null), 404);
    }

    @Test
    void aSharedDashboardRetainsAnUnavailableWidgetWithoutDisclosingItsDefinition() throws Exception {
        long id = report("Private source");
        var made = send(author, post(BOARDS), boardBody("Panel", id, null));
        assertStatus(made, 201);
        Map<String, Object> board = TestSession.object(made);
        String path = BOARDS + "/" + board.get("id");
        long revision = ((Number) board.get("revision")).longValue();
        share(path, revision, role(reader));
        var found = send(viewer, get(path), null);
        assertStatus(found, 200);
        assertThat(found.getContentAsString())
                .contains("\"available\":false")
                .doesNotContain("Private source", "\"state\"", "\"entity\"");
        assertStatus(
                send(viewer, put(path).header("If-Match", "\"" + (revision + 1) + "\""), boardBody("Stolen", id, null)),
                404);
        assertStatus(send(author, delete(path), null), 428);
    }

    @Test
    void aDashboardPeriodNeverSilentlyLeavesAnUnmappedWidgetUnfiltered() throws Exception {
        long id = report("Totals");
        var made = send(author, post(BOARDS), boardBody("Period", id, null));
        assertStatus(made, 201);
        Map<String, Object> board = TestSession.object(made);
        String widget = BOARDS + "/" + board.get("id") + "/widgets/" + id + "/result";
        String revision = "\"" + board.get("revision") + "\"";
        assertStatus(send(author, get(widget), null), 428);
        assertStatus(send(author, get(widget).header("If-Match", "\"99\""), null), 409);
        assertStatus(send(author, get(widget).header("If-Match", revision), null), 200);
        assertStatus(
                send(
                        author,
                        get(widget)
                                .header("If-Match", revision)
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-31"),
                        null),
                422);
    }

    @Test
    void sharingRolesHaveTheirOwnBoundedSettingsPermission() throws Exception {
        assertStatus(send(author, get("/api/v1/report-sharing/roles").param("limit", "1"), null), 200);
        assertStatus(send(author, get("/api/v1/report-sharing/roles").param("limit", "101"), null), 422);
        assertStatus(send(viewer, get("/api/v1/report-sharing/roles"), null), 403);
    }

    @Test
    void changingTheDefaultInvalidatesThePreviousDefaultRevision() throws Exception {
        var first = send(author, post(VIEWS), Map.of("name", "First " + tag, "state", Map.of(), "isDefault", true));
        assertStatus(first, 201);
        long id = ((Number) TestSession.object(first).get("id")).longValue();
        assertStatus(
                send(author, post(VIEWS), Map.of("name", "Second " + tag, "state", Map.of(), "isDefault", true)), 201);
        var listed = send(author, get(VIEWS), null);
        var matching = TestSession.array(listed).stream()
                .map(value -> (Map<?, ?>) value)
                .filter(value -> ((Number) value.get("id")).longValue() == id)
                .findFirst()
                .orElseThrow();
        assertThat(((Number) matching.get("lockVersion")).intValue()).isEqualTo(1);
    }

    @Test
    void preferenceMutationsNeverReplayAuthorizationSensitiveAnswers() throws Exception {
        assertStatus(author.send(post(VIEWS), reportBody("Replay")), 400);
        assertStatus(author.send(post(BOARDS), Map.of("name", "Replay " + tag, "widgets", List.of())), 400);
        long id = report("Sharing key");
        assertStatus(
                author.send(
                        put(VIEWS + "/" + id + "/sharing").header("If-Match", "0"),
                        Map.of("roleIds", List.of(role(reader)))),
                400);
    }

    @Test
    void sharingRequiresCurrentRevisionAndRejectsTablesAndInvalidRoles() throws Exception {
        long id = report("Sharing");
        String path = VIEWS + "/" + id + "/sharing";
        assertStatus(send(author, put(path), Map.of("roleIds", List.of())), 428);
        assertStatus(send(author, put(path).header("If-Match", "9"), Map.of("roleIds", List.of())), 409);
        assertStatus(
                send(author, put(path).header("If-Match", "0"), Map.of("roleIds", List.of(role(reader), role(reader)))),
                422);
        assertStatus(send(author, put(path).header("If-Match", "0"), Map.of("roleIds", List.of(999999))), 422);
        var table = send(author, post(VIEWS), Map.of("name", "Table " + tag, "state", Map.of()));
        assertStatus(table, 201);
        String tablePath = VIEWS + "/" + TestSession.object(table).get("id") + "/sharing";
        assertStatus(send(author, get(tablePath), null), 404);
    }

    @Test
    void namedDashboardPagesAndDeletedSourcesRemainRecoverable() throws Exception {
        long id = report("Deleted source");
        var first = send(author, post(BOARDS), boardBody("First", id, "orderDate"));
        assertStatus(first, 201);
        assertStatus(send(author, post(BOARDS), boardBody("Second", id, null)), 201);
        var page = send(author, get(BOARDS).param("limit", "1"), null);
        assertStatus(page, 200);
        String cursor = (String) TestSession.object(page).get("nextCursor");
        assertThat(cursor).isNotBlank();
        var next = send(author, get(BOARDS).param("limit", "1").param("cursor", cursor), null);
        assertStatus(next, 200);
        assertThat(TestSession.object(next).get("items"))
                .isNotEqualTo(TestSession.object(page).get("items"));
        String panel = BOARDS + "/" + TestSession.object(first).get("id");
        assertStatus(send(author, delete(VIEWS + "/" + id).header("If-Match", "0"), null), 204);
        var unavailable = send(author, get(panel), null);
        assertStatus(unavailable, 200);
        assertThat(unavailable.getContentAsString())
                .contains("\"available\":false")
                .doesNotContain("Deleted source", "orderDate");
        assertStatus(send(author, put(panel).header("If-Match", "1"), boardBody("Renamed", id, null)), 200);
    }

    @Test
    void convertingAReportToATableDoesNotRetainLatentSharing() throws Exception {
        long id = report("Kind change");
        share(VIEWS + "/" + id, 0, role(reader));
        assertStatus(
                send(
                        author,
                        put(VIEWS + "/" + id).header("If-Match", "1"),
                        Map.of("name", "Kind change " + tag, "kind", "table", "state", Map.of())),
                200);
        assertStatus(send(author, put(VIEWS + "/" + id).header("If-Match", "2"), reportBody("Kind change")), 200);
        assertStatus(send(viewer, get("/api/v1/entities/" + ENTITY + "/reports/" + id), null), 404);
    }

    private long report(String name) throws Exception {
        var response = send(author, post(VIEWS), reportBody(name));
        assertStatus(response, 201);
        return ((Number) TestSession.object(response).get("id")).longValue();
    }

    private Map<String, Object> reportBody(String name) {
        return Map.of("name", name + " " + tag, "kind", "report", "state", Map.of("chart", "kpi"));
    }

    private Map<String, Object> boardBody(String name, long id, String periodField) {
        Map<String, Object> widget = periodField == null
                ? Map.of("viewId", id, "width", 1)
                : Map.of("viewId", id, "width", 1, "periodField", periodField);
        return Map.of("name", name + " " + tag, "widgets", List.of(widget));
    }

    private void share(String resource, long revision, long role) throws Exception {
        assertStatus(
                send(
                        author,
                        put(resource + "/sharing").header("If-Match", "\"" + revision + "\""),
                        Map.of("roleIds", List.of(role))),
                200);
    }

    private long role(TestUser user) {
        return jdbc.sql("select role_id from md_user_roles where user_id = :id")
                .param("id", user.id())
                .query(Long.class)
                .single();
    }

    private static MockHttpServletResponse send(TestSession session, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        return session.send(request, body, null);
    }

    private static void assertStatus(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    }
}
