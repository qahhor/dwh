package com.smartup24.cms.instance.analytics.api;

public record UserWorkloadDto(
        long userId, String userName, String userLogin, long assignedTasks, long completedTasks) {}
