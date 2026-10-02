package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.ms.task.service.MsTaskStatusEntity;
import com.smartup24.cms.instance.ms.task.service.MsTaskTypeEntity;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
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
 * The task types and statuses on the general runtime (ADR-0032, 8, steps 1 and 2; plan 10/10, item 5.6): what their
 * hooks and the order action add to the contract — a new item goes last, a taken code is refused on the field, the
 * order is one action per move, a system item is neither archived nor deleted, and an item in use is not deleted but
 * archived.
 */
class MsTaskDictionaryIntegrationTest extends EmbeddedPostgresTest {

    private static final String STATUSES = "/api/v1/entities/" + MsTaskStatusEntity.CODE;
    private static final String TYPES = "/api/v1/entities/" + MsTaskTypeEntity.CODE;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    private TestSession admin;
    private String tag;

    @BeforeEach
    void signIn() throws Exception {
        TestUsers users = TestUsers.of(wac);
        var user = users.withRights(
                Map.of(
                        "tasks.statuses", Set.of("view", "create", "update", "delete"),
                        "tasks.types", Set.of("view", "create", "update", "delete")),
                users.unit("dict-" + UUID.randomUUID().toString().substring(0, 6)));
        admin = TestSession.signIn(wac, user.login());
        tag = "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    @DisplayName("5.6: a new status goes after the last one, and a taken code is refused on the field")
    void newItemsGoLastAndCodesAreUnique() throws Exception {
        int last = jdbc.sql("select max(sort_order) from ms_task_statuses")
                .query(Integer.class)
                .single();
        Map<String, Object> created = created(STATUSES, Map.of("code", tag, "name", "Review", "color", "#112233"));
        assertThat(((Number) created.get("sortOrder")).intValue()).isEqualTo(last + 10);
        assertThat(created).containsEntry("terminal", false).containsEntry("system", false);

        MockHttpServletResponse twice =
                admin.send(post(STATUSES), Map.of("code", tag, "name", "Again", "color", "#112233"));
        assertThat(twice.getStatus()).isEqualTo(422);
        assertThat(twice.getContentAsString()).contains("error.task.status_code_exists");

        long id = number(created.get("id"));
        MockHttpServletResponse renamed =
                admin.send(patch(STATUSES + "/" + id).header("If-Match", "\"1\""), Map.of("code", tag + "x"));
        assertThat(renamed.getStatus()).as("the code is kept after creation").isEqualTo(422);
        assertThat(renamed.getContentAsString()).contains("readonly");

        Map<String, Object> named = created(STATUSES, Map.of("name", "Named only"));
        assertThat((String) named.get("code"))
                .as("a code made for a status named by a person")
                .matches("^status_[0-9a-f]{8}$");
        assertThat(named).containsEntry("color", "#64748b");
    }

    @Test
    @DisplayName("5.6: move puts a type at its place and the others apart, each with a new revision")
    void moveReordersTheTypes() throws Exception {
        long first =
                number(created(TYPES, Map.of("code", tag + "a", "name", "A")).get("id"));
        long second =
                number(created(TYPES, Map.of("code", tag + "b", "name", "B")).get("id"));
        List<Long> before = order();
        long revision = revision("ms_task_types", second);

        MockHttpServletResponse moved = admin.send(
                post(TYPES + "/" + second + "/actions/move").header("If-Match", "\"" + revision + "\""),
                Map.of("position", 1));

        assertThat(moved.getStatus()).as(moved.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(moved)).containsEntry("sortOrder", 10);
        List<Long> after = order();
        assertThat(after.getFirst()).isEqualTo(second);
        assertThat(after).containsExactlyInAnyOrderElementsOf(before);
        assertThat(after.indexOf(first)).isGreaterThan(0);

        MockHttpServletResponse nowhere = admin.send(
                post(TYPES + "/" + first + "/actions/move")
                        .header("If-Match", "\"" + revision("ms_task_types", first) + "\""),
                Map.of("position", 0));
        assertThat(nowhere.getStatus()).isEqualTo(422);
        assertThat(nowhere.getContentAsString()).contains("params.position");
    }

    @Test
    @DisplayName("5.6: a system status is neither archived nor deleted; a status in use is not deleted")
    void systemAndUsedStatusesStay() throws Exception {
        long initial = jdbc.sql("select id from ms_task_statuses where code = 'new'")
                .query(Long.class)
                .single();
        String ifMatch = "\"" + revision("ms_task_statuses", initial) + "\"";
        MockHttpServletResponse archived = admin.send(
                put(STATUSES + "/" + initial + "/archived").header("If-Match", ifMatch), Map.of("archived", true));
        assertThat(archived.getStatus()).isEqualTo(409);
        assertThat(archived.getContentAsString()).contains("error.task.status_system_archive");
        MockHttpServletResponse deleted = admin.send(delete(STATUSES + "/" + initial));
        assertThat(deleted.getStatus()).isEqualTo(409);
        assertThat(deleted.getContentAsString()).contains("error.task.status_system_delete");

        long own = number(created(STATUSES, Map.of("code", tag, "name", "Own", "color", "#445566"))
                .get("id"));
        long user = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        jdbc.sql("insert into ms_tasks (title, status_code, reporter_id, created_by) values ('used', :status, :user,"
                        + " :user)")
                .param("status", tag)
                .param("user", user)
                .update();
        MockHttpServletResponse used = admin.send(delete(STATUSES + "/" + own));
        assertThat(used.getStatus()).isEqualTo(409);
        assertThat(used.getContentAsString()).contains("error.task.status_in_use");

        MockHttpServletResponse archivedOwn = admin.send(
                put(STATUSES + "/" + own + "/archived")
                        .header("If-Match", "\"" + revision("ms_task_statuses", own) + "\""),
                Map.of("archived", true));
        assertThat(archivedOwn.getStatus())
                .as("an item in use is archived instead")
                .isEqualTo(200);
    }

    private Map<String, Object> created(String collection, Map<String, Object> body) throws Exception {
        MockHttpServletResponse response = admin.send(post(collection), body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return TestSession.object(response);
    }

    private List<Long> order() {
        return jdbc.sql("select id from ms_task_types where archived_at is null order by sort_order, id")
                .query(Long.class)
                .list();
    }

    private long revision(String table, long id) {
        return jdbc.sql("select revision from " + table + " where id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
