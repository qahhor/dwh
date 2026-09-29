package com.smartup24.cms.instance.ms.task.repository;

import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The flat filters the task list took before the registry, kept so the task screen, its kanban and the lookups keep
 * working. {@code memberRole} narrows {@code assignedUserId}: R, E, O, or both R and E.
 */
public record LegacyTaskFilters(
        Long projectId,
        Long statusId,
        String priority,
        Boolean hideTerminal,
        Long assignedUserId,
        String memberRole,
        Long reporterId,
        Boolean overdue) {

    public static LegacyTaskFilters none() {
        return new LegacyTaskFilters(null, null, null, null, null, null, null, null);
    }

    /** Canonical form for the cursor fingerprint; null when no filter is set. */
    public String canonical() {
        StringBuilder value = new StringBuilder();
        if (projectId != null) value.append(";project=").append(projectId);
        if (statusId != null) value.append(";status=").append(statusId);
        else if (Boolean.TRUE.equals(hideTerminal)) value.append(";active");
        if (priority != null && !priority.isBlank()) value.append(";priority=").append(priority.strip());
        if (assignedUserId != null)
            value.append(";member=").append(assignedUserId).append(':').append(role());
        if (reporterId != null) value.append(";reporter=").append(reporterId);
        if (Boolean.TRUE.equals(overdue)) value.append(";overdue");
        return value.isEmpty() ? null : value.toString();
    }

    /**
     * The data scope (ADR-0013) and these filters as one predicate for the registry page. They go into the same SQL,
     * so a page and its total only ever see visible tasks.
     */
    public QueryPlan.SqlFragment listPredicate(ScopeFilter scope) {
        StringBuilder sql = new StringBuilder(scope.sql());
        Map<String, Object> params = new LinkedHashMap<>();
        if (scope.bindsUserId()) params.put("scopeUserId", scope.userId());
        if (projectId != null) {
            sql.append(" and t.project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (statusId != null) {
            sql.append(" and t.status_id = :statusId");
            params.put("statusId", statusId);
        } else if (Boolean.TRUE.equals(hideTerminal)) {
            sql.append(" and t.status_id not in (select id from ms_task_statuses where is_terminal = true)");
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" and t.priority = :priority");
            params.put("priority", priority.strip());
        }
        if (assignedUserId != null) {
            String kinds = switch (role()) {
                case "R" -> "('R')";
                case "E" -> "('E')";
                case "O" -> "('O')";
                default -> "('R', 'E')";
            };
            sql.append(" and exists (select 1 from ms_task_members m where m.task_id = t.id"
                    + " and m.user_id = :assignedUserId and m.involve_kind in " + kinds + ")");
            params.put("assignedUserId", assignedUserId);
        }
        if (reporterId != null) {
            sql.append(" and (t.reporter_id = :reporterId or t.created_by = :reporterId)");
            params.put("reporterId", reporterId);
        }
        if (Boolean.TRUE.equals(overdue)) {
            sql.append(" and t.end_time is not null and t.end_time < now()"
                    + " and t.status_id not in (select id from ms_task_statuses where is_terminal = true)");
        }
        return new QueryPlan.SqlFragment(sql.toString(), params);
    }

    private String role() {
        String role = memberRole == null ? "" : memberRole.strip().toUpperCase(Locale.ROOT);
        return switch (role) {
            case "R", "E", "O" -> role;
            default -> "RE";
        };
    }
}
