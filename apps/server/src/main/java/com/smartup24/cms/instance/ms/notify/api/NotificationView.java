package com.smartup24.cms.instance.ms.notify.api;

import java.time.Instant;

/** An in-app notification of the inbox. */
public record NotificationView(
        Long id,
        Long userId,
        String type,
        String title,
        String body,
        String formLink,
        String sourceCode,
        boolean isRead,
        Instant createdAt) {}
