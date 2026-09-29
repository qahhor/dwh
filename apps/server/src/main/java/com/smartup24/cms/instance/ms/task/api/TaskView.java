package com.smartup24.cms.instance.ms.task.api;

import java.time.Instant;
import java.util.Map;

/** A task as the API answers it: the list row, the card and the subtask and ancestor entries. */
public record TaskView(
        Long id,
        Long projectId,
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
