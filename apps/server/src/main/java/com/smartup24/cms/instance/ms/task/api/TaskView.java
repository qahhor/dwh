package com.smartup24.cms.instance.ms.task.api;

import java.time.Instant;
import java.util.Map;

/**
 * A task as the API answers it: the list row, the card and the subtask and ancestor entries. {@code projectName}
 * names the project, so a screen shows it without reading every project (plan 10/10, item 3.5).
 */
public record TaskView(
        Long id,
        Long projectId,
        String projectName,
        Long parentTaskId,
        String title,
        String descriptionMarkdown,
        Long statusId,
        String priority,
        Long reporterId,
        Map<String, Object> attributes,
        Instant beginTime,
        Instant endTime,
        Instant resolvedTime,
        Instant createdAt,
        Instant modifiedAt,
        Long createdBy,
        Long modifiedBy,
        Long revision) {}
