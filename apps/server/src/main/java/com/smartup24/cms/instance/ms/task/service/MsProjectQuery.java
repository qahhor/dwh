package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListExtender;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The project list in the field registry (roadmap item 51): {@code GET /api/v1/tasks/projects/page} and
 * {@code /api/v1/query-meta/ms.projects}. By name by default, as the whole list was. The whole list
 * ({@code GET /tasks/projects}) stays for the pickers.
 *
 * <p>Task counts and progress are counted over the tasks the viewer may see (ADR-0013), so they are built for
 * each request by {@link #progressFields} and exist only for someone who may view tasks (ADR-0016, 2.9).
 */
@Configuration
public class MsProjectQuery {

    public static final String COLUMNS = """
            p.id, p.name, p.description, p.state, p.attributes::text as attributes_str, p.created_at, p.created_by""";

    public static final QueryList LIST = new QueryList(
                    "ms.projects",
                    MsTaskPref.FORM_PROJECTS,
                    "view",
                    COLUMNS,
                    "ms_task_projects p",
                    "p.id",
                    List.of(
                            QueryField.of("id", "projects.col.id", QueryFieldType.NUMBER, "p.id")
                                    .asSortable(),
                            QueryField.of("name", "projects.col.name", QueryFieldType.TEXT, "p.name")
                                    .asSortable()
                                    .asSearchable(),
                            QueryField.of(
                                            "description",
                                            "projects.col.description",
                                            QueryFieldType.TEXT,
                                            "p.description")
                                    .asNullable()
                                    .asSearchable()
                                    .asHidden(),
                            QueryField.enumeration(
                                    "state", "projects.col.state", "p.state", List.of("A", "P"), "projects.state."),
                            QueryField.of(
                                            "createdAt",
                                            "projects.col.created_at",
                                            QueryFieldType.INSTANT,
                                            "p.created_at")
                                    .asSortable()),
                    "name",
                    false,
                    QueryList.DEFAULT_LIMIT,
                    QueryList.MAX_LIMIT)
            .withCustomFields("PROJECT", "p.attributes");

    @Bean
    public QueryList msProjectsQueryList() {
        return LIST;
    }

    /**
     * Total tasks, closed tasks (in a terminal status, as the statistics count them) and progress in per cent,
     * each over the viewer's tasks. The scope predicate binds {@code :scopeUserId}; the page supplies it.
     */
    @Bean
    public QueryListExtender progressFields(MdScopeService scopes) {
        return list -> {
            if (!LIST.code().equals(list.code()) || !SecurityContext.isAuthenticated()) {
                return List.of();
            }
            ScopeFilter scope = scopes.filterForTasks(SecurityContext.getCurrentUserId());
            String tasks = "from ms_tasks t where t.project_id = p.id" + scope.sql();
            String total = "(select count(*) " + tasks + ")";
            String done = "(select count(*) " + tasks
                    + " and t.status_id in (select s.id from ms_task_statuses s where s.is_terminal))";
            String progress =
                    "(case when " + total + " = 0 then 0 else round(" + done + " * 100.0 / " + total + ") end)";
            return List.of(
                    QueryField.of("totalTasks", "projects.col.total_tasks", QueryFieldType.NUMBER, total)
                            .asHidden()
                            .requires(MsTaskPref.FORM_TASKS, "view"),
                    QueryField.of("doneTasks", "projects.col.done_tasks", QueryFieldType.NUMBER, done)
                            .asHidden()
                            .requires(MsTaskPref.FORM_TASKS, "view"),
                    QueryField.of("progress", "projects.col.progress", QueryFieldType.NUMBER, progress)
                            .asSortable()
                            .requires(MsTaskPref.FORM_TASKS, "view"));
        };
    }
}
