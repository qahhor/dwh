package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryList;
import com.greenwhite.dwh.instance.ms.task.pref.MsTaskPref;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
                    QueryField.of("id", "tasks.col.id", QueryFieldType.NUMBER, "t.id").asSortable(),
                    QueryField.of("title", "tasks.col.title", QueryFieldType.TEXT, "t.title")
                            .asSortable().asSearchable(),
                    QueryField.of("descriptionMarkdown", "tasks.col.description", QueryFieldType.TEXT,
                            "t.description_markdown").asSearchable().asHidden(),
                    QueryField.of("projectId", "tasks.col.project", QueryFieldType.NUMBER, "t.project_id")
                            .asNullable(),
                    QueryField.enumeration("priority", "tasks.col.priority", "t.priority",
                            List.of(MsTaskPref.PRIORITY_LOW, MsTaskPref.PRIORITY_MEDIUM, MsTaskPref.PRIORITY_HIGH,
                                    MsTaskPref.PRIORITY_CRITICAL), "tasks.priority."),
                    QueryField.of("statusId", "tasks.col.status", QueryFieldType.NUMBER, "t.status_id"),
                    QueryField.of("endTime", "tasks.col.due", QueryFieldType.INSTANT, "t.end_time").asNullable(),
                    QueryField.of("beginTime", "tasks.col.begin", QueryFieldType.INSTANT, "t.begin_time")
                            .asNullable().asHidden(),
                    QueryField.of("reporterId", "tasks.col.reporter", QueryFieldType.NUMBER, "t.reporter_id")
                            .asHidden(),
                    QueryField.of("createdAt", "tasks.col.created_at", QueryFieldType.INSTANT, "t.created_at")
                            .asSortable().asHidden(),
                    QueryField.of("modifiedAt", "tasks.col.modified_at", QueryFieldType.INSTANT, "t.modified_at")
                            .asSortable().asHidden()),
            "id",
            false,
            QueryList.DEFAULT_LIMIT,
            QueryList.MAX_LIMIT).withCustomFields("TASK", "t.attributes");

    @Bean
    public QueryList msTasksQueryList() {
        return LIST;
    }
}
