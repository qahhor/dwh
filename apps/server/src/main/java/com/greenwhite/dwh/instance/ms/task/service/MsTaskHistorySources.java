package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.history.RecordHistorySource;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.ms.task.pref.MsTaskPref;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** The task module's records with a history tab (ADR-0017): tasks and projects. */
@Configuration
public class MsTaskHistorySources {

    @Bean
    RecordHistorySource taskHistorySource(MsTaskService taskService) {
        return new RecordHistorySource() {
            public String key() { return "tasks"; }
            public String tableName() { return "ms_tasks"; }
            public String form() { return MsTaskPref.FORM_TASKS; }
            public String action() { return "view"; }

            /** The same data scope as opening the task. */
            public void requireVisible(String recordId) {
                taskService.getTaskById(numericId(recordId, ErrorCode.TASK_NOT_FOUND), SecurityContext.getCurrentUserId());
            }

            public Map<String, String> fieldLabels() {
                return Map.of(
                        "title", "task.title",
                        "priority", "common.priority",
                        "projectId", "projects.proekt",
                        "statusId", "common.status",
                        "descriptionMarkdown", "task.description");
            }
        };
    }

    @Bean
    RecordHistorySource projectHistorySource(MsProjectService projectService) {
        return new RecordHistorySource() {
            public String key() { return "projects"; }
            public String tableName() { return "ms_task_projects"; }
            public String form() { return MsTaskPref.FORM_PROJECTS; }
            public String action() { return "view"; }

            public void requireVisible(String recordId) {
                projectService.getProjectById(numericId(recordId, ErrorCode.PROJECT_NOT_FOUND));
            }

            public Map<String, String> fieldLabels() {
                return Map.of(
                        "name", "projects.nazvanie_proekta",
                        "description", "projects.opisanie",
                        "state", "common.status");
            }
        };
    }

    /** A malformed id cannot name a record, so it is as good as not found. */
    static Long numericId(String recordId, ErrorCode notFound) {
        try {
            return Long.valueOf(recordId);
        } catch (NumberFormatException e) {
            throw ApiException.notFound(notFound, "Запись не найдена");
        }
    }
}
