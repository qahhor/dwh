package com.smartup24.cms.instance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.security.ScopeFixture.Kind;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.ms.task.service.MsTaskService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * ADR-0013, "404, not 403" for a project named in a request body or a filter: a project outside the caller's data
 * scope answers exactly like a missing one, so a task cannot reveal it, attach to it, or make it visible by
 * participation. The caller is the matrix viewer: every administrator permission and the rule UNITS on unit A.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopeProjectReferenceIntegrationTest extends EmbeddedPostgresTest {

    /** The problem fields a client can read; volatile ones (instance, trace) are left out. */
    private static final List<String> ANSWER_FIELDS =
            List.of("type", "title", "status", "detail", "code", "messageKey", "params", "errors");

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdUserService users;

    @Autowired
    private MdScopeService scopes;

    @Autowired
    private MdScopeRepository scopeRepository;

    @Autowired
    private MdRoleRepository roles;

    @Autowired
    private MsTaskService tasks;

    @Autowired
    private MfFileService files;

    private ScopeFixture fixture;
    private ScopeSession viewer;
    private long outsideProject;
    private long insideProject;
    private long missingProject;

    @BeforeAll
    void setUp() throws Exception {
        fixture = new ScopeFixture(jdbc, users, scopes, scopeRepository, roles, tasks, files);
        viewer = ScopeSession.signIn(wac, fixture.viewerLogin);
        outsideProject = (Long) fixture.create(Kind.PROJECT, false);
        insideProject = (Long) fixture.create(Kind.PROJECT, true);
        // The hidden project has a task of its own whose people all stand outside the viewer's scope.
        tasks.createTask(
                outsideProject,
                null,
                "TEST hidden task",
                "",
                "medium",
                null,
                null,
                null,
                null,
                null,
                null,
                fixture.outsider);
        missingProject = jdbc.sql("select coalesce(max(id), 0) + 1000000 from ms_task_projects")
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("ADR-0013: creating a task in a project outside the scope answers like a missing project")
    void createInHiddenProjectAnswersLikeMissing() throws Exception {
        MockHttpServletResponse hidden = viewer.send(post("/api/v1/tasks"), createBody(outsideProject));
        MockHttpServletResponse missing = viewer.send(post("/api/v1/tasks"), createBody(missingProject));

        assertThat(hidden.getStatus()).as(hidden.getContentAsString()).isEqualTo(404);
        assertThat(answer(hidden)).isEqualTo(answer(missing));
        assertHiddenProjectUntouched();
    }

    @Test
    @DisplayName("ADR-0013: moving a visible task into a project outside the scope answers like a missing project")
    void moveIntoHiddenProjectAnswersLikeMissing() throws Exception {
        long task = (Long) fixture.create(Kind.TASK, true);

        MockHttpServletResponse hidden = movePatch(task, outsideProject);
        MockHttpServletResponse missing = movePatch(task, missingProject);

        assertThat(hidden.getStatus()).as(hidden.getContentAsString()).isEqualTo(404);
        assertThat(answer(hidden)).isEqualTo(answer(missing));
        assertThat(jdbc.sql("select count(*) from ms_tasks where id = :id and project_id is null")
                        .param("id", task)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertHiddenProjectUntouched();
        // A project in the scope still takes the task: the check is the scope, not a blanket refusal.
        assertThat(movePatch(task, insideProject).getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("ADR-0013: filtering the task list by a project outside the scope answers like a missing project")
    void filterByHiddenProjectAnswersLikeMissing() throws Exception {
        MockHttpServletResponse hidden = viewer.send(get("/api/v1/tasks").param("projectId", "" + outsideProject));
        MockHttpServletResponse missing = viewer.send(get("/api/v1/tasks").param("projectId", "" + missingProject));

        assertThat(hidden.getStatus()).as(hidden.getContentAsString()).isEqualTo(200);
        assertThat(missing.getStatus()).isEqualTo(200);
        assertThat(json(hidden).path("items").size()).isZero();
        assertThat(json(missing).path("items").size()).isZero();
    }

    private MockHttpServletResponse movePatch(long task, long project) throws Exception {
        return viewer.send(
                patch("/api/v1/tasks/{id}", task).header("If-Match", fixture.ifMatch(Kind.TASK, task)),
                Map.of("projectId", project));
    }

    /** Nothing reached the hidden project, and it is still invisible to the viewer. */
    private void assertHiddenProjectUntouched() throws Exception {
        assertThat(jdbc.sql("select count(*) from ms_tasks where project_id = :project")
                        .param("project", outsideProject)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(viewer.send(get("/api/v1/tasks/projects/{id}", outsideProject))
                        .getStatus())
                .isEqualTo(404);
    }

    private static Map<String, Object> createBody(long project) {
        return Map.of("title", "TEST project reference", "projectId", project);
    }

    private static Map<String, String> answer(MockHttpServletResponse response) throws Exception {
        JsonNode problem = json(response);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("httpStatus", String.valueOf(response.getStatus()));
        for (String field : ANSWER_FIELDS) {
            fields.put(field, problem.path(field).toString());
        }
        return fields;
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return ScopeSession.JSON.readTree(response.getContentAsString());
    }
}
