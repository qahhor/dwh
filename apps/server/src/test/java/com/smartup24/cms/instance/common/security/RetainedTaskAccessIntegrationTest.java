package com.smartup24.cms.instance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/** ADR-0013: removing the last participation also closes retained copies of a task. */
class RetainedTaskAccessIntegrationTest extends EmbeddedPostgresTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdScopeService scopes;

    @Autowired
    private MdRoleRepository roles;

    @Autowired
    private MdPermissionService permissions;

    @Autowired
    private JobRunner jobs;

    @Autowired
    private MfFileService files;

    private TestSession viewer;
    private TestSession author;
    private TestUsers.TestUser actor;
    private long ownerId;
    private long task;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("delete from fnd_job_queue").update();
        TestUsers users = TestUsers.of(wac);
        long unit = users.unit("retained-task");
        actor = users.withRights(Map.of("tasks.items", List.of("view", "update"), "mf.files", List.of("view")), unit);
        long role = roles.getUserRoleIds(actor.id()).getFirst();
        scopes.setRoleRule(role, "SELF", roles.findById(role).orElseThrow().revision());
        var owner = users.withRightsOf("chief_admin", unit);
        ownerId = owner.id();
        viewer = TestSession.signIn(wac, actor.login());
        author = TestSession.signIn(wac, owner.login());
        var response = author.send(
                post("/api/v1/entities/ms.tasks"), Map.of("title", "Retained task", "responsibleId", actor.id()));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        task = ((Number) TestSession.object(response).get("id")).longValue();
        assertThat(viewer.send(get(path())).getStatus()).isEqualTo(200);
    }

    @Test
    void emptyOptionalReferenceRemovesParticipationWithoutAServerError() throws Exception {
        var current = author.send(get(path()));
        var response =
                author.send(patch(path()).header("If-Match", current.getHeader("ETag")), Map.of("responsibleId", ""));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(response).get("responsibleId")).isNull();
        assertThat(viewer.send(get(path())).getStatus()).isEqualTo(404);
    }

    @Test
    void readyExportChecksCurrentRowsEvenWhenThePermissionVersionDidNotChange() throws Exception {
        var queued = viewer.send(post("/api/v1/exports"), Map.of("list", "ms.tasks", "columns", List.of("title")));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        String file = "/api/v1/exports/" + TestSession.object(queued).get("id") + "/file";
        jobs.runQueued();
        assertThat(viewer.send(get(file)).getStatus()).isEqualTo(200);

        removeParticipation();

        assertThat(viewer.send(get(file)).getStatus())
                .as("stored rows need their current scope")
                .isEqualTo(403);
    }

    @Test
    void readyFileExportChecksTheTaskThatMadeTheFileVisible() throws Exception {
        byte[] content = ("retained-file-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        var stored =
                files.upload("retained.txt", "text/plain", new ByteArrayInputStream(content), content.length, ownerId);
        var attached = author.send(post("/api/v1/tasks/" + task + "/files"), Map.of("fileId", stored.id()));
        assertThat(attached.getStatus()).as(attached.getContentAsString()).isEqualTo(204);
        assertThat(viewer.send(get("/api/v1/files/" + stored.id())).getStatus()).isEqualTo(200);
        var queued =
                viewer.send(post("/api/v1/exports"), Map.of("list", "mf.files", "columns", List.of("originalName")));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        String file = "/api/v1/exports/" + TestSession.object(queued).get("id") + "/file";
        jobs.runQueued();
        assertThat(viewer.send(get(file)).getStatus()).isEqualTo(200);

        removeParticipation();

        assertThat(viewer.send(get("/api/v1/files/" + stored.id())).getStatus()).isEqualTo(404);
        assertThat(viewer.send(get(file)).getStatus()).isEqualTo(403);
    }

    @Test
    void commentReplayNeedsOnlyTheCommentsPermissionOfTheOriginalEndpoint() throws Exception {
        var commenter = TestUsers.of(wac).withRights(Map.of("tasks.comments", List.of("create")), actor.unit());
        TestSession comments = TestSession.signIn(wac, commenter.login());
        assertThat(comments.send(get(path())).getStatus()).isEqualTo(404);
        String path = "/api/v1/tasks/" + task + "/comments";
        Map<String, Object> body = Map.of("textMarkdown", "Retained comment");
        UUID key = UUID.randomUUID();
        var first = comments.send(post(path), body, key);
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
        var replay = comments.send(post(path), body, key);
        assertThat(replay.getStatus()).as(replay.getContentAsString()).isEqualTo(201);
        assertThat(replay.getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(TestSession.object(replay)).isEqualTo(TestSession.object(first));
    }

    @Test
    void replayChecksCurrentRowsWithoutRepeatingTheMutation() throws Exception {
        UUID key = UUID.randomUUID();
        Map<String, Object> body = Map.of("title", "Saved while visible");
        var changed = viewer.send(patch(path()).header("If-Match", "\"1\""), body, key);
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(viewer.send(patch(path()).header("If-Match", "\"1\""), body, key)
                        .getHeader("Idempotent-Replay"))
                .isEqualTo("true");

        removeParticipation();

        var refused = viewer.send(patch(path()).header("If-Match", "\"1\""), body, key);
        assertThat(refused.getStatus()).as("stored record is no longer visible").isEqualTo(409);
        assertThat(refused.getContentAsString()).doesNotContain("Saved while visible");
        assertThat(refused.getHeader("Idempotent-Replay")).isNull();
        assertThat(jdbc.sql("select revision from ms_tasks where id = :id")
                        .param("id", task)
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    private void removeParticipation() throws Exception {
        long version = permissions.getPermissionVersion(actor.id());
        var current = author.send(get(path()));
        var changed = author.send(
                patch(path()).header("If-Match", current.getHeader("ETag")), Map.of("responsibleId", ownerId));
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(permissions.getPermissionVersion(actor.id())).isEqualTo(version);
        assertThat(viewer.send(get(path())).getStatus()).isEqualTo(404);
    }

    private String path() {
        return "/api/v1/entities/ms.tasks/" + task;
    }
}
