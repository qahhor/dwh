package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.query.QueryListExporter;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository.LegacyTaskFilters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The task list as an export (ADR-0018): the viewer's data scope and the screen's flat filters, as on the screen. */
@Configuration
public class MsTaskListExporters {

    private static final List<String> NUMBERS = List.of("project_id", "status_id", "assigned_user_id", "reporter_id");
    private static final List<String> FLAGS = List.of("hide_terminal", "overdue");

    @Bean
    public QueryListExporter msTasksExporter(MsTaskListService tasks) {
        return new QueryListExporter() {
            public String code() { return MsTaskQuery.LIST.code(); }

            public Set<String> options() {
                return Set.of("project_id", "status_id", "priority", "hide_terminal", "assigned_user_id",
                        "member_role", "reporter_id", "overdue");
            }

            public List<FieldErrorItem> checkOptions(Map<String, String> options) {
                List<FieldErrorItem> errors = new ArrayList<>();
                for (String key : NUMBERS) {
                    String value = options.get(key);
                    if (value != null && !value.isBlank() && !value.strip().matches("\\d{1,18}")) {
                        errors.add(new FieldErrorItem(key, "EXPORT_INVALID", "not a number: " + value));
                    }
                }
                for (String key : FLAGS) {
                    String value = options.get(key);
                    if (value != null && !value.isBlank() && !List.of("true", "false").contains(value.strip())) {
                        errors.add(new FieldErrorItem(key, "EXPORT_INVALID", "true or false"));
                    }
                }
                String priority = options.get("priority");
                if (priority != null && !priority.isBlank()
                        && !MsTaskQuery.LIST.field("priority").orElseThrow().enumValues().contains(priority.strip())) {
                    errors.add(new FieldErrorItem("priority", "EXPORT_INVALID", "unknown priority"));
                }
                String role = options.get("member_role");
                if (role != null && !role.isBlank() && !List.of("R", "E", "O").contains(role.strip())) {
                    errors.add(new FieldErrorItem("member_role", "EXPORT_INVALID", "R, E or O"));
                }
                return errors;
            }

            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search,
                                      Map<String, String> options) {
                return tasks.page(SecurityContext.getCurrentUserId(), limit, cursor, filter, sort, search,
                        new LegacyTaskFilters(number(options.get("project_id")), number(options.get("status_id")),
                                options.get("priority"), flag(options.get("hide_terminal")),
                                number(options.get("assigned_user_id")), options.get("member_role"),
                                number(options.get("reporter_id")), flag(options.get("overdue"))));
            }
        };
    }

    /** Checked by {@code checkOptions} before the job starts. */
    private static Long number(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value.strip());
    }

    private static Boolean flag(String value) {
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.strip());
    }
}
