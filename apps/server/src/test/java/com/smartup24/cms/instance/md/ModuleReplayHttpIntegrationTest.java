package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/** FR-MOD-01: a stored response obeys the module switch without repeating an accepted operation. */
class ModuleReplayHttpIntegrationTest extends EmbeddedPostgresTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ModuleRegistryService modules;

    @Autowired
    private JdbcClient jdbc;

    private TestSession session;
    private final String tag = "replay-" + UUID.randomUUID();

    @BeforeEach
    void signIn() throws Exception {
        TestUsers users = TestUsers.of(context);
        var admin = users.withRightsOf("admin", users.unit("module-replay"));
        session = TestSession.signIn(context, admin.login());
    }

    @ParameterizedTest
    @CsvSource({"notes,ms.notes,ms_notes", "tasks,ms.tasks,ms_tasks"})
    void entityCreateReplayIsHiddenWhileItsModuleIsOff(String module, String code, String table) throws Exception {
        var body = code.equals("ms.tasks") ? Map.of("title", tag, "typeCode", "task") : Map.of("title", tag);
        replay(module, "/api/v1/entities/" + code, body, 201);
        assertThat(jdbc.sql("select count(*) from " + table + " where title = :title")
                        .param("title", tag)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void customTaskCommentReplayIsHiddenWhileTasksAreOff() throws Exception {
        var task = session.send(post("/api/v1/entities/ms.tasks"), Map.of("title", tag, "typeCode", "task"));
        assertThat(task.getStatus()).as(task.getContentAsString()).isEqualTo(201);
        Object taskId = TestSession.object(task).get("id");
        replay("tasks", "/api/v1/tasks/" + taskId + "/comments", Map.of("textMarkdown", tag), 201);
        assertThat(jdbc.sql("select count(*) from ms_task_comments where text_markdown = :text")
                        .param("text", tag)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void customUploadSourceReplayIsHiddenWhileUplIsOff() throws Exception {
        replay(
                "upl",
                "/api/v1/upl/sources",
                Map.of("code", tag, "name", tag, "ownerOrg", "Fixture", "periodicity", "adhoc", "sourceType", "file"),
                201);
        assertThat(jdbc.sql("select count(*) from upl_sources where code = :code")
                        .param("code", tag)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void exportReceiptReplayIsHiddenWhileItsEntityModuleIsOff() throws Exception {
        replay("notes", "/api/v1/exports", Map.of("list", "ms.notes", "q", tag, "lang", "en"), 202);
        assertThat(jdbc.sql("select count(*) from report_exports where request ->> 'q' = :q")
                        .param("q", tag)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    private void replay(String module, String path, Object body, int status) throws Exception {
        UUID key = UUID.randomUUID();
        var original = session.send(post(path), body, key);
        assertThat(original.getStatus()).as(original.getContentAsString()).isEqualTo(status);
        var switched = session.send(put("/api/v1/modules/" + module + "/enabled"), Map.of("enabled", false));
        assertThat(switched.getStatus()).as(switched.getContentAsString()).isEqualTo(200);
        try {
            var refused = session.send(post(path), body, key);
            assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(404);
            assertThat(refused.getContentType()).startsWith("application/problem+json");
            assertThat(refused.getHeader("Idempotent-Replay")).isNull();
            assertThat(refused.getHeader("Location")).isNull();
            assertThat(refused.getContentAsString()).doesNotContain(tag);
        } finally {
            modules.toggleModuleStatus(module, true);
        }
        var restored = session.send(post(path), body, key);
        assertThat(restored.getStatus()).as(restored.getContentAsString()).isEqualTo(status);
        assertThat(restored.getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(restored.getHeader("Location")).isEqualTo(original.getHeader("Location"));
        assertThat(TestSession.object(restored)).isEqualTo(TestSession.object(original));
    }
}
