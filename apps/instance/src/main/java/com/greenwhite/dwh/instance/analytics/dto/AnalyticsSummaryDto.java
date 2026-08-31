package com.greenwhite.dwh.instance.analytics.dto;

public record AnalyticsSummaryDto(
        long totalTasks,
        long activeTasks,
        long completedTasks,
        long overdueTasks,
        double completionRatePercent,
        long createdLast7d,
        long completedLast7d,
        long activeProjectsCount,
        long activeUsersCount
) {}
