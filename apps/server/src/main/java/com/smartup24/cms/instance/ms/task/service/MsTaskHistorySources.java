package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.history.RecordHistorySource;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The task module's records with a history tab of its own (ADR-0017): tasks; projects have the runtime's. */
@Configuration
public class MsTaskHistorySources {

    @Bean
    RecordHistorySource taskHistorySource(MsTaskService taskService) {
        return new RecordHistorySource() {
            public String key() {
                return "tasks";
            }

            public String tableName() {
                return "ms_tasks";
            }

            public String form() {
                return MsTaskPref.FORM_TASKS;
            }

            public String action() {
                return "view";
            }

            /** The same data scope as opening the task. */
            public void requireVisible(String recordId) {
                taskService.getTaskById(
                        numericId(recordId, ErrorCode.TASK_NOT_FOUND), SecurityContext.getCurrentUserId());
            }

            public Map<String, String> fieldLabels() {
                return Map.of(
                        "title", "task.title",
                        "priority", "common.priority",
                        "projectId", "projects.common.project",
                        "statusId", "common.status",
                        "descriptionMarkdown", "task.description");
            }
        };
    }

    /** A malformed id cannot name a record, so it is as good as not found. */
    static Long numericId(String recordId, ErrorCode notFound) {
        try {
            return Long.valueOf(recordId);
        } catch (NumberFormatException e) {
            throw ApiException.notFound(notFound, "error.task.record_not_found");
        }
    }
}
