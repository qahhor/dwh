package com.greenwhite.dwh.instance.config.cp;

public record CpTelemetrySnapshot(
        long activeUsers,
        long outboxPending,
        long outboxDeadLetter,
        long storageUsedBytes,
        long storageQuotaBytes) {
}
