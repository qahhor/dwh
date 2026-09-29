package com.smartup24.cms.instance.ms.task.api;

/**
 * The flat filters the task list took before the registry (ADR-0016), kept for the task screen, its kanban and the
 * lookups. {@code memberRole} narrows {@code assignedUserId}: R, E, O, or both R and E.
 */
public record TaskListFilters(
        Long projectId,
        Long statusId,
        String priority,
        Boolean hideTerminal,
        Long assignedUserId,
        String memberRole,
        Long reporterId,
        Boolean overdue) {}
