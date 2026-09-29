package com.smartup24.cms.instance.audit.api;

import java.time.Instant;

/** The audit counters and when they were computed (they are cached for a short time). */
public record AuditStatsView(
        long totalAuditLogs,
        long totalSecurityEvents,
        long securityEventsLast24h,
        long failedLoginsLast24h,
        Instant computedAt) {}
