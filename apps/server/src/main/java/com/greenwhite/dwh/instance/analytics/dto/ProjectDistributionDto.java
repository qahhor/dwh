package com.greenwhite.dwh.instance.analytics.dto;

public record ProjectDistributionDto(
        Long projectId,
        String projectName,
        long totalTasks,
        long activeTasks,
        long completedTasks,
        double progressPercent
) {}
