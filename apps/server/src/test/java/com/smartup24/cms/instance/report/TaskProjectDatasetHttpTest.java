package com.smartup24.cms.instance.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/** The selected business dataset counts tasks, retaining the projects' current access (ADR-0039). */
class TaskProjectDatasetHttpTest extends EmbeddedPostgresTest {
    private static final String PATH = "/api/v1/entities/ms.tasks";
    private static final String DATASET = "ms.tasks.projects";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdPermissionService permissions;

    @Autowired
    private ModuleRegistryService modules;

    private TestSession session;
    private TestUser reader;
    private String tag;

    @BeforeEach
    void setup() throws Exception {
        modules.toggleModuleStatus("tasks", true);
        var users = TestUsers.of(context);
        tag = UUID.randomUUID().toString();
        reader = users.withRights(
                Map.of(
                        "tasks.items",
                        Set.of("view", "create"),
                        "tasks.projects",
                        Set.of("view", "create"),
                        "md.profile",
                        Set.of("view", "update")),
                users.unit("tasks-dataset-" + tag));
        session = TestSession.signIn(context, reader.login());
    }

    @Test
    void selectedDatasetProvidesProjectDatesAndPreservesTaskCountsInSavedReportAndWorkbook() throws Exception {
        var catalog = session.send(get(PATH + "/report-datasets"));
        status(catalog, 200);
        assertThat(catalog.getContentAsString()).contains(DATASET, "tasks.dataset.projects");
        var metadata = session.send(get(PATH + "/report-meta").param("dataset", DATASET));
        status(metadata, 200);
        assertThat(metadata.getContentAsString())
                .contains(
                        "projectCreatedAt",
                        "projectModifiedAt",
                        "tasks.dataset.project_created_at",
                        "tasks.dataset.project_modified_at");
        long project = project();
        task(project);
        task(project);
        task(null);
        Map<String, Object> state = Map.of(
                "dataset",
                DATASET,
                "chart",
                "line",
                "groupBy",
                List.of(Map.of("field", "projectCreatedAt", "trunc", "day")),
                "measures",
                List.of(Map.of("op", "count")),
                "filter",
                List.of(Map.of("field", "title", "op", "eq", "value", tag)));
        var saved = session.send(
                post("/api/v1/list-views/ms.tasks"), Map.of("name", tag, "kind", "report", "state", state), null);
        status(saved, 201);
        long id = ((Number) TestSession.object(saved).get("id")).longValue();
        var report = session.send(get(PATH + "/reports/" + id));
        status(report, 200);
        assertThat(TestSession.object(report).get("rows"))
                .isEqualTo(List.of(
                        Map.of("groups", List.of("2026-01-01"), "values", List.of(2)),
                        Map.of("groups", Arrays.asList((Object) null), "values", List.of(1))));
        var export = session.send(get(PATH + "/reports/" + id + "/export"));
        status(export, 200);
        assertThat(AggregateXlsxCells.data(export.getContentAsByteArray()))
                .containsEntry("A1", new AggregateXlsxCells.Cell("text", "Проект: создан (day)"))
                .containsEntry("B2", new AggregateXlsxCells.Cell("number", "2"))
                .containsEntry("B3", new AggregateXlsxCells.Cell("number", "1"));
        jdbc.sql("update ms_task_projects set archived_at=now(), archived_by=created_by where id=:id")
                .param("id", project)
                .update();
        var archived = session.send(get(PATH + "/reports/" + id));
        status(archived, 200);
        assertThat(TestSession.object(archived).get("rows"))
                .isEqualTo(List.of(Map.of("groups", Arrays.asList((Object) null), "values", List.of(3))));
    }

    @Test
    void projectViewRevocationClosesDatasetForTheExistingSession() throws Exception {
        status(session.send(get(PATH + "/report-meta").param("dataset", DATASET)), 200);
        jdbc.sql("delete from md_role_permissions where form_code='tasks.projects' and action='view' "
                        + "and role_id in (select role_id from md_user_roles where user_id=:user)")
                .param("user", reader.id())
                .update();
        permissions.recalculateEffectivePermissions(reader.id());
        status(session.send(get(PATH + "/report-meta").param("dataset", DATASET)), 404);
        status(session.send(get(PATH + "/report").param("dataset", DATASET)), 404);
        status(session.send(get(PATH + "/report/export").param("dataset", DATASET)), 404);
        assertThat(session.send(get(PATH + "/report-datasets")).getContentAsString())
                .doesNotContain(DATASET);
    }

    private long project() throws Exception {
        var response = session.send(post("/api/v1/entities/ms.projects"), Map.of("name", tag));
        status(response, 201);
        long id = ((Number) TestSession.object(response).get("id")).longValue();
        jdbc.sql("update ms_task_projects set created_at='2026-01-01T12:00:00Z', modified_at=created_at where id=:id")
                .param("id", id)
                .update();
        return id;
    }

    private void task(Long project) throws Exception {
        status(
                session.send(
                        post(PATH),
                        project == null ? Map.of("title", tag) : Map.of("title", tag, "projectId", project)),
                201);
    }

    private static void status(MockHttpServletResponse response, int code) throws Exception {
        assertThat(response.getStatus()).as("HTTP status").isEqualTo(code);
    }
}
