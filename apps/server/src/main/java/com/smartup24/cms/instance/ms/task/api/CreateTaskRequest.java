package com.smartup24.cms.instance.ms.task.api;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CreateTaskRequest(
        @NotBlank String title,
        String descriptionMarkdown,
        Long projectId,
        Long parentTaskId,
        String priority,
        Long responsibleUserId,
        List<Long> executorUserIds,
        List<Long> observerUserIds,
        Map<String, Object> attributes,
        Instant beginTime,
        Instant endTime) {}
