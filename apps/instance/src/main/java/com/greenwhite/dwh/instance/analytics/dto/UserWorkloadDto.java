package com.greenwhite.dwh.instance.analytics.dto;

public record UserWorkloadDto(
        long userId,
        String userName,
        String userLogin,
        long assignedTasks,
        long completedTasks
) {}
