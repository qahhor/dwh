package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryListExtender;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The task counts of the project list ({@code ms.projects}, the list of {@link MsProjectEntity}): total tasks, closed
 * tasks and progress in per cent, each over the tasks the viewer may see (ADR-0013). They depend on the viewer's task
 * scope, so they are built for each request — filterable and sortable list fields only for someone who may view
 * tasks (ADR-0016, 2.9); their values are read with {@code GET /api/v1/tasks/projects/progress}.
 */
@Configuration
public class MsProjectQuery {

    /**
     * The fields over the viewer's tasks. The scope predicate binds {@code :scopeUserId}: the project scope of the
     * same viewer binds it whenever the task scope needs it, as both follow the viewer's one rule.
     */
    @Bean
    public QueryListExtender progressFields(MdScopeService scopes) {
        return list -> {
            if (!MsProjectEntity.CODE.equals(list.code()) || !SecurityContext.isAuthenticated()) {
                return List.of();
            }
            ScopeFilter scope = scopes.filterForTasks(SecurityContext.getCurrentUserId());
            String tasks = "from ms_tasks t where t.project_id = p.id" + scope.sql();
            String total = "(select count(*) " + tasks + ")";
            String done = "(select count(*) " + tasks
                    + " and t.status_code in (select s.code from ms_task_statuses s where s.is_terminal))";
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
