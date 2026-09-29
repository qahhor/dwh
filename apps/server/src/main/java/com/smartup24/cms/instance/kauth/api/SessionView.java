package com.smartup24.cms.instance.kauth.api;

import java.time.Instant;

/** A sign-in session as an administrator sees it; the token hash and authentication version stay on the server. */
public record SessionView(
        Long id,
        Long userId,
        String ip,
        String userAgent,
        String deviceInfo,
        Instant createdAt,
        Instant lastSeenAt,
        Instant closedAt) {}
