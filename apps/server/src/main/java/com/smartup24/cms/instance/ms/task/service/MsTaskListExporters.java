package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.task.repository.LegacyTaskFilters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The task list as an export (ADR-0018): the viewer's data scope and the screen's flat filters, as on the screen. */
@Configuration
public class MsTaskListExporters {

    private static final List<String> NUMBERS = List.of("projectId", "statusId", "assignedUserId", "reporterId");
    private static final List<String> FLAGS = List.of("hideTerminal", "overdue");
    private static final Set<String> TASK_OPTIONS = Set.of(
            "projectId",
            "statusId",
            "priority",
            "hideTerminal",
            "assignedUserId",
            "memberRole",
            "reporterId",
            "overdue");

    @Bean
    public QueryListExporter msTasksExporter(MsTaskListService tasks) {
        return new QueryListExporter() {
            public String code() {
                return MsTaskQuery.LIST.code();
            }

            public Set<String> options() {
                return TASK_OPTIONS;
            }

            public List<FieldErrorItem> checkOptions(Map<String, String> options) {
                return checkTaskOptions(options);
            }

            public KeysetPage<?> page(
                    int limit, String cursor, String filter, String sort, String search, Map<String, String> options) {
                return tasks.page(
                        SecurityContext.getCurrentUserId(),
                        limit,
                        cursor,
                        filter,
                        sort,
                        search,
                        new LegacyTaskFilters(
                                number(options.get("projectId")),
                                number(options.get("statusId")),
                                options.get("priority"),
                                flag(options.get("hideTerminal")),
                                number(options.get("assignedUserId")),
                                options.get("memberRole"),
                                number(options.get("reporterId")),
                                flag(options.get("overdue"))));
            }
        };
    }

    /** The screen's flat filters, each checked before the export job starts. */
    static List<FieldErrorItem> checkTaskOptions(Map<String, String> options) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (String key : NUMBERS) {
            String value = options.get(key);
            if (value != null && !value.isBlank() && !value.strip().matches("\\d{1,18}")) {
                errors.add(FieldErrorItem.keyed(
                        key, "EXPORT_INVALID", "error.field.not_a_number", Map.of("value", value)));
            }
        }
        for (String key : FLAGS) {
            String value = options.get(key);
            if (value != null && !value.isBlank() && !List.of("true", "false").contains(value.strip())) {
                errors.add(FieldErrorItem.keyed(key, "EXPORT_INVALID", "error.field.true_or_false"));
            }
        }
        String priority = options.get("priority");
        if (priority != null
                && !priority.isBlank()
                && !MsTaskQuery.LIST
                        .field("priority")
                        .orElseThrow()
                        .enumValues()
                        .contains(priority.strip())) {
            errors.add(FieldErrorItem.keyed(
                    "priority",
                    "EXPORT_INVALID",
                    "error.field.one_of",
                    Map.of(
                            "values",
                            String.join(
                                    ", ",
                                    MsTaskQuery.LIST
                                            .field("priority")
                                            .orElseThrow()
                                            .enumValues()))));
        }
        String role = options.get("memberRole");
        if (role != null && !role.isBlank() && !List.of("R", "E", "O").contains(role.strip())) {
            errors.add(FieldErrorItem.keyed(
                    "memberRole", "EXPORT_INVALID", "error.field.one_of", Map.of("values", "R, E, O")));
        }
        return errors;
    }

    /** Checked by {@code checkOptions} before the job starts. */
    private static Long number(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value.strip());
    }

    private static Boolean flag(String value) {
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.strip());
    }
}
