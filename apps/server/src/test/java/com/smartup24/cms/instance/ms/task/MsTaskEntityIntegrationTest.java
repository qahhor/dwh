package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskEntity;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tasks on the general runtime (ADR-0032, 8, step 4; plan 10/10, item 5.6): what the task's hooks and its record action
 * add to the entity contract — the participation rows follow the record, a participant the author may not see and a
 * parent cycle are refused on the field with nothing written, {@code set_status} resolves and reopens a task, the list
 * keeps the screen's presets as filter expressions, and a bulk action runs record by record.
 */
class MsTaskEntityIntegrationTest extends EmbeddedPostgresTest {

    private static final String TASKS = "/api/v1/entities/" + MsTaskEntity.CODE;
    private static final Map<String, Set<String>> ALL = Map.of("tasks.items", Set.of("view", "create", "update"));

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    private TestUsers users;
    private TestUser author;
    private TestUser colleague;
    private TestSession session;
    private String tag;

    @BeforeEach
    void signIn() throws Exception {
        users = TestUsers.of(wac);
        tag = UUID.randomUUID().toString().substring(0, 8);
        author = users.withRights(ALL, users.unit("tasks-a-" + tag));
        colleague = users.withRights(Map.of(), author.unit());
        session = TestSession.signIn(wac, author.login());
    }

    @Test
    @DisplayName("FR-TASK-4: the author, the responsible person, the executors and the observers take part")
    void participantsFollowTheRecord() throws Exception {
        TestUser observer = users.withRights(Map.of(), author.unit());
        Map<String, Object> task = created(Map.of(
                "title", "People " + tag,
                "responsibleId", colleague.id(),
                "executorIds", List.of(colleague.id()),
                "observerIds", List.of(observer.id())));
        long id = number(task.get("id"));
        assertThat(task).containsEntry("statusCode", "new").containsEntry("typeCode", "task");
        assertThat(number(task.get("reporterId"))).isEqualTo(author.id());
        assertThat(members(id, MsTaskPref.INVOLVE_AUTHOR)).containsExactly(author.id());
        assertThat(members(id, MsTaskPref.INVOLVE_RESPONSIBLE)).containsExactly(colleague.id());
        assertThat(members(id, MsTaskPref.INVOLVE_EXECUTOR)).containsExactly(colleague.id());
        assertThat(members(id, MsTaskPref.INVOLVE_OBSERVER)).containsExactly(observer.id());

        changed(id, Map.of("title", "Still observed " + tag));
        assertThat(members(id, MsTaskPref.INVOLVE_OBSERVER))
                .as("an omitted list keeps its people")
                .containsExactly(observer.id());

        Map<String, Object> cleared = new HashMap<>();
        cleared.put("observerIds", List.of());
        cleared.put("responsibleId", null);
        changed(id, cleared);
        assertThat(members(id, MsTaskPref.INVOLVE_OBSERVER)).isEmpty();
        assertThat(members(id, MsTaskPref.INVOLVE_RESPONSIBLE)).isEmpty();
        assertThat(members(id, MsTaskPref.INVOLVE_AUTHOR)).containsExactly(author.id());
    }

    @Test
    @DisplayName("ADR-0013: a participant the author may not see is refused on the field and nothing is written")
    void hiddenParticipantRollsBack() throws Exception {
        long id = number(created(Map.of("title", "Original " + tag, "responsibleId", colleague.id()))
                .get("id"));
        long audits = audits(id);
        TestUser stranger = users.withRights(Map.of(), users.unit("tasks-b-" + tag));

        MockHttpServletResponse refused = change(id, Map.of("title", "Must roll back", "responsibleId", stranger.id()));

        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("responsibleId", "error.task.assignee_unavailable");
        assertThat(jdbc.sql("select title from ms_tasks where id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo("Original " + tag);
        assertThat(members(id, MsTaskPref.INVOLVE_RESPONSIBLE)).containsExactly(colleague.id());
        assertThat(audits(id)).isEqualTo(audits);
    }

    @Test
    @DisplayName("FR-TASK-8: a parent that is the task itself or one of its subtasks is refused")
    void parentCyclesAreRefused() throws Exception {
        long parent = number(created(Map.of("title", "Parent " + tag)).get("id"));
        long child = number(
                created(Map.of("title", "Child " + tag, "parentTaskId", parent)).get("id"));

        MockHttpServletResponse cycle = change(parent, Map.of("parentTaskId", child));
        assertThat(cycle.getStatus()).as(cycle.getContentAsString()).isEqualTo(422);
        assertThat(cycle.getContentAsString()).contains("parentTaskId", "error.task.parent_cycle");
        assertThat(change(parent, Map.of("parentTaskId", parent)).getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("set_status: a terminal status resolves the task, another reopens it, an unknown one is refused")
    void setStatusResolvesAndReopens() throws Exception {
        long id = number(created(Map.of("title", "Status " + tag)).get("id"));

        Map<String, Object> done = TestSession.object(ok(setStatus(id, "done")));
        assertThat(done).containsEntry("statusCode", "done");
        assertThat(done.get("resolvedTime")).isNotNull();

        Map<String, Object> reopened = TestSession.object(ok(setStatus(id, "in_progress")));
        assertThat(reopened).containsEntry("statusCode", "in_progress");
        assertThat(reopened.get("resolvedTime")).isNull();

        MockHttpServletResponse unknown = setStatus(id, "no_such_status");
        assertThat(unknown.getStatus()).isEqualTo(422);
        assertThat(unknown.getContentAsString()).contains("params.status", "error.task.field_status_unknown");
    }

    @Test
    @DisplayName("ADR-0016: the presets of the screen are filter expressions of the list")
    void presetsAreFilterExpressions() throws Exception {
        long open = number(created(Map.of("title", "Open " + tag, "responsibleId", author.id()))
                .get("id"));
        long late = number(created(Map.of("title", "Late " + tag, "endTime", "2020-01-01T00:00:00Z"))
                .get("id"));
        long closed = number(created(Map.of("title", "Closed " + tag, "endTime", "2020-01-01T00:00:00Z"))
                .get("id"));
        ok(setStatus(closed, "done"));

        assertThat(ids(field("terminal", false))).containsExactlyInAnyOrder(open, late);
        assertThat(ids(field("overdue", true))).containsExactly(late);
        assertThat(ids(field("responsibleId", author.id()))).containsExactly(open);
    }

    @Test
    @DisplayName("bulk: set_status and update run record by record; a missing task fails alone")
    void bulkRunsRecordByRecord() throws Exception {
        long first = number(created(Map.of("title", "Bulk one " + tag)).get("id"));
        long second = number(created(Map.of("title", "Bulk two " + tag)).get("id"));
        long missing = 999_999_999L;

        Map<String, Object> moved = TestSession.object(ok(session.send(
                post(TASKS + "/bulk"),
                Map.of(
                        "action",
                        MsTaskEntity.SET_STATUS,
                        "ids",
                        List.of(first, missing, second),
                        "params",
                        Map.of("status", "done")))));
        assertThat(moved).containsEntry("succeeded", 2).containsEntry("failed", 1);
        assertThat(column(first, "status_code")).isEqualTo("done");
        assertThat(column(second, "status_code")).isEqualTo("done");

        Map<String, Object> prioritized = TestSession.object(ok(session.send(
                post(TASKS + "/bulk"),
                Map.of("action", "update", "ids", List.of(first, second), "params", Map.of("priority", "critical")))));
        assertThat(prioritized).containsEntry("succeeded", 2);
        assertThat(column(first, "priority")).isEqualTo("critical");

        TestSession reader = TestSession.signIn(
                wac,
                users.withRights(Map.of("tasks.items", Set.of("view")), author.unit())
                        .login());
        assertThat(reader.send(
                                post(TASKS + "/bulk"),
                                Map.of(
                                        "action",
                                        MsTaskEntity.SET_STATUS,
                                        "ids",
                                        List.of(first),
                                        "params",
                                        Map.of("status", "new")))
                        .getStatus())
                .as("a bulk change needs the right to change tasks")
                .isEqualTo(403);
        assertThat(column(first, "status_code")).isEqualTo("done");
    }

    private Map<String, Object> created(Map<String, Object> values) throws Exception {
        Map<String, Object> body = new HashMap<>(values);
        body.putIfAbsent("typeCode", "task");
        MockHttpServletResponse response = session.send(post(TASKS), body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return TestSession.object(response);
    }

    private MockHttpServletResponse change(long id, Map<String, @Nullable Object> values) throws Exception {
        return session.send(patch(TASKS + "/" + id).header("If-Match", revision(id)), values);
    }

    private void changed(long id, Map<String, @Nullable Object> values) throws Exception {
        ok(change(id, values));
    }

    private MockHttpServletResponse setStatus(long id, String status) throws Exception {
        return session.send(
                post(TASKS + "/" + id + "/actions/" + MsTaskEntity.SET_STATUS).header("If-Match", revision(id)),
                Map.of("status", status));
    }

    private List<Long> ids(String condition) throws Exception {
        String title = "{\"field\":\"title\",\"op\":\"contains\",\"value\":\"" + tag + "\"}";
        Map<String, Object> page =
                TestSession.object(ok(session.send(get(TASKS).param("filter", "[" + title + "," + condition + "]"))));
        return ((List<?>) page.get("items"))
                .stream().map(item -> number(((Map<?, ?>) item).get("id"))).toList();
    }

    private static String field(String key, Object value) {
        return "{\"field\":\"" + key + "\",\"op\":\"eq\",\"value\":" + value + "}";
    }

    private String revision(long id) {
        return "\"" + column(id, "revision") + "\"";
    }

    private String column(long id, String column) {
        return jdbc.sql("select " + column + "::text from ms_tasks where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private List<Long> members(long task, String kind) {
        return jdbc.sql("select user_id from ms_task_members where task_id = :task and involve_kind = :kind"
                        + " order by user_id")
                .param("task", task)
                .param("kind", kind)
                .query(Long.class)
                .list();
    }

    private long audits(long task) {
        return jdbc.sql("select count(*) from audit_log where table_name = 'ms_tasks' and row_pk = :pk")
                .param("pk", Long.toString(task))
                .query(Long.class)
                .single();
    }

    private static MockHttpServletResponse ok(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response;
    }

    private static long number(@Nullable Object value) {
        return ((Number) value).longValue();
    }
}
