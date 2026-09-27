package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryRef;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The task list in the field registry (ADR-0016, roadmap item 49): {@code GET /api/v1/tasks} and
 * {@code /api/v1/query-meta/ms.tasks}. Ordered by number by default, 50 rows a page, as before, so the table
 * and the kanban keep their order. Status, project and reporter are ids until lists can refer to each other
 * (roadmap item 53); the screen draws them by name.
 */
@Configuration
public class MsTaskQuery {

    public static final QueryList LIST = new QueryList(
                    "ms.tasks",
                    MsTaskPref.FORM_TASKS,
                    "view",
                    MsTaskRepository.LIST_COLUMNS,
                    "ms_tasks t",
                    "t.id",
                    List.of(
                            QueryField.of("id", "tasks.col.id", QueryFieldType.NUMBER, "t.id")
                                    .asSortable(),
                            QueryField.of("title", "tasks.col.title", QueryFieldType.TEXT, "t.title")
                                    .asSortable()
                                    .asSearchable(),
                            QueryField.of(
                                            "descriptionMarkdown",
                                            "tasks.col.description",
                                            QueryFieldType.TEXT,
                                            "t.description_markdown")
                                    .asSearchable()
                                    .asHidden(),
                            QueryField.of("projectId", "tasks.col.project", QueryFieldType.NUMBER, "t.project_id")
                                    .asNullable()
                                    .refersTo(QueryRef.whole("/tasks/projects", "name")),
                            QueryField.enumeration(
                                    "priority",
                                    "tasks.col.priority",
                                    "t.priority",
                                    List.of(
                                            MsTaskPref.PRIORITY_LOW,
                                            MsTaskPref.PRIORITY_MEDIUM,
                                            MsTaskPref.PRIORITY_HIGH,
                                            MsTaskPref.PRIORITY_CRITICAL),
                                    "tasks.priority."),
                            QueryField.of("statusId", "tasks.col.status", QueryFieldType.NUMBER, "t.status_id")
                                    .refersTo(QueryRef.whole("/tasks/statuses", "name")),
                            QueryField.of("endTime", "tasks.col.due", QueryFieldType.INSTANT, "t.end_time")
                                    .asNullable(),
                            QueryField.of("beginTime", "tasks.col.begin", QueryFieldType.INSTANT, "t.begin_time")
                                    .asNullable()
                                    .asHidden(),
                            QueryField.of("reporterId", "tasks.col.reporter", QueryFieldType.NUMBER, "t.reporter_id")
                                    .asHidden()
                                    .refersTo(QueryRef.paged("/iam/users", "name")),
                            QueryField.of("createdAt", "tasks.col.created_at", QueryFieldType.INSTANT, "t.created_at")
                                    .asSortable()
                                    .asHidden(),
                            QueryField.of(
                                            "modifiedAt",
                                            "tasks.col.modified_at",
                                            QueryFieldType.INSTANT,
                                            "t.modified_at")
                                    .asSortable()
                                    .asHidden()),
                    "id",
                    false,
                    QueryList.DEFAULT_LIMIT,
                    QueryList.MAX_LIMIT)
            .withCustomFields("TASK", "t.attributes");

    @Bean
    public QueryList msTasksQueryList() {
        return LIST;
    }
}
