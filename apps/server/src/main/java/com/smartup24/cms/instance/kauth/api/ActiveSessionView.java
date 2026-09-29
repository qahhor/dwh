package com.smartup24.cms.instance.kauth.api;

import java.time.Instant;

/** One of the viewer's own sessions, with whether it is the session of this request. */
public record ActiveSessionView(
        Long id,
        Long userId,
        String ip,
        String userAgent,
        String deviceInfo,
        Instant createdAt,
        Instant lastSeenAt,
        Instant closedAt,
        boolean current) {}
