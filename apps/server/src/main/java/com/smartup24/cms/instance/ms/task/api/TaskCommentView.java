package com.smartup24.cms.instance.ms.task.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A comment of a task with its author and attached files. */
public record TaskCommentView(
        Long id,
        Long taskId,
        Long userId,
        String textMarkdown,
        List<UUID> fileIds,
        Instant createdAt,
        String userName,
        String userLogin) {}
