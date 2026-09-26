package com.greenwhite.dwh.instance.ms.task;

import com.greenwhite.dwh.instance.support.TestDatabases;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.query.QueryCompiler;
import com.greenwhite.dwh.instance.common.query.QueryListRepository;
import com.greenwhite.dwh.instance.md.repository.MdOrgUnitRepository;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdScopeRepository;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository.LegacyTaskFilters;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskListExporters;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskListService;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The task list on the field registry (ADR-0016, roadmap item 49), with the flat filters it took before. */
class MsTaskListIntegrationTest {

    static JdbcClient jdbc;
    static MsTaskListService tasks;
    static Long viewer;
    static Long other;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("dwh_task_list_test");
        jdbc = JdbcClient.create(ds);
        var audit = new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null, new AuditDataRedactor());
        var roles = new MdRoleRepository(jdbc);
        var scope = new MdScopeService(new MdScopeRepository(jdbc), new MdOrgUnitRepository(jdbc),
                new MdPermissionService(new MdPermissionRepository(jdbc)), audit);
        tasks = new MsTaskListService(new QueryListRepository(jdbc), new MsTaskRepository(jdbc, new ObjectMapper()), scope);

        viewer = createUser("tl_viewer");
        other = createUser("tl_other");
        roles.assignRolesToUser(viewer, List.of(roles.findByPcode("admin").orElseThrow().id()));
        scope.recalculateFor(viewer);

        Long open = status(false);
        Long closed = status(true);
        Long alpha = createTask("tl Alpha", "plain", open, "high", viewer, "now() + interval '1 day'");
        Long beta = createTask("tl Beta", "mentions the needle", open, "low", other, "now() - interval '1 day'");
        Long gamma = createTask("tl Gamma", "", closed, "critical", other, "now() - interval '1 day'");
        member(alpha, other, "R");
        member(beta, other, "E");
        member(gamma, other, "O");
    }

    @Test
    @DisplayName("Ordered by number by default, as before, with the real total")
    void orderedByNumberWithTheTotal() {
        var page = tasks.page(viewer, null, null, null, null, "tl ", LegacyTaskFilters.none());
        assertThat(titles(page.items())).containsExactly("tl Alpha", "tl Beta", "tl Gamma");
        assertThat(page.totalEstimated()).isEqualTo(3);
        assertThat(titles(tasks.page(viewer, null, null, null, "-title", "tl ", LegacyTaskFilters.none()).items()))
                .containsExactly("tl Gamma", "tl Beta", "tl Alpha");
    }

    @Test
    @DisplayName("Search q looks in the title and the description")
    void searchLooksInTitleAndDescription() {
        assertThat(titles(tasks.page(viewer, null, null, null, null, "needle", LegacyTaskFilters.none()).items()))
                .containsExactly("tl Beta");
    }

    @Test
    @DisplayName("The DSL and the old flat filters narrow the same list")
    void dslAndFlatFiltersNarrowTheList() {
        assertThat(titles(tasks.page(viewer, null, null,
                "[{\"field\":\"priority\",\"op\":\"in\",\"value\":[\"high\",\"critical\"]}]", null, "tl ",
                LegacyTaskFilters.none()).items())).containsExactly("tl Alpha", "tl Gamma");
        assertThat(titles(tasks.page(viewer, null, null, null, null, "tl ", filters(null, true, null, null, null, null))
                .items())).containsExactly("tl Alpha", "tl Beta");
        assertThat(titles(tasks.page(viewer, null, null, null, null, "tl ", filters("low", null, null, null, null, null))
                .items())).containsExactly("tl Beta");
        assertThat(titles(tasks.page(viewer, null, null, null, null, "tl ", filters(null, null, other, null, null, null))
                .items())).containsExactly("tl Alpha", "tl Beta");
        assertThat(titles(tasks.page(viewer, null, null, null, null, "tl ", filters(null, null, other, "O", null, null))
                .items())).containsExactly("tl Gamma");
        assertThat(titles(tasks.page(viewer, null, null, null, null, "tl ", filters(null, null, null, null, viewer, null))
                .items())).containsExactly("tl Alpha");
        assertThat(titles(tasks.page(viewer, null, null, null, null, "tl ", filters(null, null, null, null, null, true))
                .items())).containsExactly("tl Beta");
    }

    @Test
    @DisplayName("A cursor continues only the same filters, including the flat ones")
    void cursorBelongsToItsFilters() {
        var first = tasks.page(viewer, 1, null, null, null, "tl ", LegacyTaskFilters.none());
        assertThat(titles(tasks.page(viewer, 1, first.nextCursor(), null, null, "tl ", LegacyTaskFilters.none())
                .items())).containsExactly("tl Beta");
        assertThatThrownBy(() -> tasks.page(viewer, 1, first.nextCursor(), null, null, "tl ",
                filters(null, true, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getFieldErrors())
                        .extracting(FieldErrorItem::code).containsExactly(QueryCompiler.INVALID_CURSOR));
    }

    @Test
    @DisplayName("Every registry field is a property of the row the client receives")
    void everyFieldIsARowProperty() {
        var properties = java.util.Arrays.stream(TaskRecord.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(MsTaskQuery.LIST.fields()).allSatisfy(field -> assertThat(properties).contains(field.key()));
    }

    @Test
    @DisplayName("The export refuses bad option values before the job starts")
    void exportChecksOptionValues() {
        var exporter = new MsTaskListExporters().msTasksExporter(tasks);
        assertThat(exporter.checkOptions(Map.of("status_id", "3", "priority", "high", "hide_terminal", "true",
                "member_role", "E"))).isEmpty();
        assertThat(exporter.checkOptions(Map.of("status_id", "x", "priority", "urgent", "overdue", "yes",
                "member_role", "Z"))).extracting(FieldErrorItem::field)
                .containsExactlyInAnyOrder("status_id", "priority", "overdue", "member_role");
    }

    private static LegacyTaskFilters filters(String priority, Boolean hideTerminal, Long member, String role,
                                             Long reporter, Boolean overdue) {
        return new LegacyTaskFilters(null, null, priority, hideTerminal, member, role, reporter, overdue);
    }

    private static List<String> titles(List<TaskRecord> items) {
        return items.stream().map(TaskRecord::title).toList();
    }

    private static Long status(boolean terminal) {
        return jdbc.sql("select id from ms_task_statuses where is_terminal = :terminal order by id limit 1")
                .param("terminal", terminal).query(Long.class).single();
    }

    private static Long createUser(String login) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC', '{}'::jsonb, false, false)
                        returning id
                        """)
                .param("login", login).query(Long.class).single();
    }

    private static Long createTask(String title, String description, Long statusId, String priority, Long reporter,
                                   String endTime) {
        return jdbc.sql("""
                        insert into ms_tasks (title, description_markdown, status_id, priority, reporter_id, attributes,
                                              end_time, created_by, modified_by)
                        values (:title, :description, :statusId, :priority, :reporter, '{}'::jsonb, %s,
                                :reporter, :reporter)
                        returning id
                        """.formatted(endTime))
                .param("title", title)
                .param("description", description)
                .param("statusId", statusId)
                .param("priority", priority)
                .param("reporter", reporter)
                .query(Long.class).single();
    }

    private static void member(Long taskId, Long userId, String kind) {
        jdbc.sql("insert into ms_task_members (task_id, user_id, involve_kind, is_viewed) values (:t, :u, :k, false)")
                .param("t", taskId).param("u", userId).param("k", kind).update();
    }
}
