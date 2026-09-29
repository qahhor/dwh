package com.smartup24.cms.instance.audit.api;

import java.time.Instant;
import java.util.Map;

/** A security event as a client sees it: credentials in its details are already masked. */
public record SecurityEventView(
        Long id,
        String eventType,
        Long userId,
        String ip,
        String userAgent,
        Map<String, Object> details,
        Instant createdAt,
        String userName,
        String userLogin) {}
