package com.smartup24.cms.instance.ms.task.api;

/** A participant of a task: responsible (R), executor (E) or observer (O). */
public record TaskMemberView(
        Long taskId,
        Long userId,
        String userName,
        String userLogin,
        String userEmail,
        String involveKind,
        boolean isViewed) {}
