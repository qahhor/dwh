package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.ms.task.service.MsProjectEntity;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * Projects on the general runtime (ADR-0032, 8, step 3; plan 10/10, item 5.6): what the project's hooks, member actions
 * and module reads add to the entity contract — a name in use is refused on the field and free again once its project
 * is archived; members are added, paged and removed by record actions, each audited as an access change; the progress
 * of a project counts only the viewer's tasks, and a project outside the viewer's scope has none; the list sorts by it,
 * and an "any" group over the archive lists every project.
 */
class MsProjectEntityIntegrationTest extends EmbeddedPostgresTest {

    private static final String PROJECTS = "/api/v1/entities/" + MsProjectEntity.CODE;
    private static final Map<String, Set<String>> ALL = Map.of(
            "tasks.projects", Set.of("view", "create", "update"), "tasks.items", Set.of("view", "create", "update"));

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    private TestUsers users;
    private TestUser owner;
    private TestSession session;
    private String tag;

    @BeforeEach
    void signIn() throws Exception {
        users = TestUsers.of(wac);
        tag = UUID.randomUUID().toString().substring(0, 8);
        owner = users.withRights(ALL, users.unit("projects-a-" + tag));
        session = TestSession.signIn(wac, owner.login());
    }

    @Test
    @DisplayName("5.6: a name in use is refused on the field, and free again once its project is archived")
    void namesInUseAreUnique() throws Exception {
        Map<String, Object> first = created(Map.of("name", "Apollo " + tag, "description", "first"));
        MockHttpServletResponse twice = session.send(post(PROJECTS), Map.of("name", "Apollo " + tag));
        assertThat(twice.getStatus()).isEqualTo(422);
        assertThat(twice.getContentAsString()).contains("error.project.name_exists");

        MockHttpServletResponse archived = session.send(
                put(PROJECTS + "/" + first.get("id") + "/archived").header("If-Match", "\"1\""),
                Map.of("archived", true));
        assertThat(archived.getStatus()).as(archived.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(archived)).containsEntry("archived", true);
        created(Map.of("name", "Apollo " + tag));
    }

    @Test
    @DisplayName("5.6: members are added, paged and removed by record actions, each change audited")
    void membersAreRecordActions() throws Exception {
        long project = number(created(Map.of("name", "Members " + tag)).get("id"));
        TestUser member = users.withRights(Map.of(), owner.unit());

        MockHttpServletResponse added = session.send(
                post(PROJECTS + "/" + project + "/actions/add_member").header("If-Match", "\"1\""),
                Map.of("userId", member.id(), "accessKind", "W"));
        assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(added)).containsEntry("revision", 2);

        Map<String, Object> page =
                TestSession.object(session.send(get("/api/v1/tasks/projects/" + project + "/members/page")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) page.get("items");
        assertThat(members)
                .singleElement()
                .satisfies(item -> assertThat(item)
                        .containsEntry("userId", (int) member.id())
                        .containsEntry("accessKind", "W"));
        assertThat(audit("I", project + ":" + member.id())).isOne();

        MockHttpServletResponse badKind = session.send(
                post(PROJECTS + "/" + project + "/actions/add_member").header("If-Match", "\"2\""),
                Map.of("userId", member.id(), "accessKind", "X"));
        assertThat(badKind.getStatus()).isEqualTo(422);
        assertThat(badKind.getContentAsString()).contains("params.accessKind");

        TestUser stranger = users.withRights(Map.of(), users.unit("projects-b-" + tag));
        MockHttpServletResponse hidden = session.send(
                post(PROJECTS + "/" + project + "/actions/add_member").header("If-Match", "\"2\""),
                Map.of("userId", stranger.id(), "accessKind", "R"));
        assertThat(hidden.getStatus()).as("a user the actor cannot see").isEqualTo(422);
        assertThat(hidden.getContentAsString()).contains("params.userId");

        MockHttpServletResponse removed = session.send(
                post(PROJECTS + "/" + project + "/actions/remove_member").header("If-Match", "\"2\""),
                Map.of("userId", member.id()));
        assertThat(removed.getStatus()).as(removed.getContentAsString()).isEqualTo(200);
        assertThat(audit("D", project + ":" + member.id())).isOne();
        assertThat((List<?>)
                        TestSession.object(session.send(get("/api/v1/tasks/projects/" + project + "/members/page")))
                                .get("items"))
                .isEmpty();
    }

    @Test
    @DisplayName("ADR-0013: progress counts the viewer's tasks; a project outside the scope has none; the list sorts"
            + " by it")
    void progressFollowsTheScope() throws Exception {
        long busy = number(created(Map.of("name", "Busy " + tag)).get("id"));
        long idle = number(created(Map.of("name", "Idle " + tag)).get("id"));
        task(busy, "done", owner.id());
        task(busy, "new", owner.id());
        TestUser outsider = users.withRights(ALL, users.unit("projects-c-" + tag));
        task(busy, "done", outsider.id());
        long foreign = jdbc.sql("insert into ms_task_projects (name, created_by) values (:name, :user) returning id")
                .param("name", "Foreign " + tag)
                .param("user", outsider.id())
                .query(Long.class)
                .single();

        MockHttpServletResponse progress =
                session.send(get("/api/v1/tasks/projects/progress").param("ids", busy + "," + idle + "," + foreign));
        assertThat(progress.getStatus()).as(progress.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.array(progress))
                .as("the viewer's two tasks of Busy, none of Idle, nothing of a project the viewer cannot see")
                .containsExactly(
                        Map.of("projectId", (int) busy, "totalTasks", 2, "doneTasks", 1, "progress", 50),
                        Map.of("projectId", (int) idle, "totalTasks", 0, "doneTasks", 0, "progress", 0));

        Map<String, Object> sorted = TestSession.object(session.send(get(PROJECTS)
                .param("sort", "-progress")
                .param("filter", "[{\"field\":\"name\",\"op\":\"contains\",\"value\":\"" + tag + "\"}]")));
        assertThat(((List<?>) sorted.get("items"))
                        .stream()
                                .map(item -> number(((Map<?, ?>) item).get("id")))
                                .toList())
                .containsExactly(busy, idle);

        TestSession viewerOnly = TestSession.signIn(
                wac,
                users.withRights(Map.of("tasks.projects", Set.of("view")), owner.unit())
                        .login());
        assertThat(viewerOnly
                        .send(get("/api/v1/tasks/projects/progress").param("ids", String.valueOf(busy)))
                        .getStatus())
                .as("task counts need the right to view tasks")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("5.4: an any group over the archive lists the archived projects with the others")
    void anyGroupListsEveryProject() throws Exception {
        long active = number(created(Map.of("name", "Active " + tag)).get("id"));
        long paused = number(created(Map.of("name", "Paused " + tag)).get("id"));
        session.send(put(PROJECTS + "/" + paused + "/archived").header("If-Match", "\"1\""), Map.of("archived", true));
        String name = "{\"field\":\"name\",\"op\":\"contains\",\"value\":\"" + tag + "\"}";
        String every = "[" + name + ",{\"any\":[{\"field\":\"archived\",\"op\":\"eq\",\"value\":true},"
                + "{\"field\":\"archived\",\"op\":\"eq\",\"value\":false}]}]";

        assertThat(ids("[" + name + "]")).containsExactly(active);
        assertThat(ids(every)).containsExactlyInAnyOrder(active, paused);
    }

    private List<Long> ids(String filter) throws Exception {
        return ((List<?>) TestSession.object(session.send(get(PROJECTS).param("filter", filter)))
                        .get("items"))
                .stream().map(item -> number(((Map<?, ?>) item).get("id"))).toList();
    }

    private Map<String, Object> created(Map<String, Object> body) throws Exception {
        MockHttpServletResponse response = session.send(post(PROJECTS), body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return TestSession.object(response);
    }

    private void task(long project, String status, long reporter) {
        jdbc.sql("""
                        insert into ms_tasks (project_id, title, status_id, reporter_id, created_by)
                        values (:project, 'TEST progress', (select id from ms_task_statuses where code = :status),
                                :reporter, :reporter)
                        """)
                .param("project", project)
                .param("status", status)
                .param("reporter", reporter)
                .update();
    }

    private long audit(String event, String recordId) {
        return jdbc.sql("select count(*) from audit_log where table_name = 'ms_task_project_members'"
                        + " and row_pk = :record and event = :event")
                .param("record", recordId)
                .param("event", event)
                .query(Long.class)
                .single();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
