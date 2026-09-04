package com.greenwhite.dwh.instance.config.system;

import java.time.Instant;

/** Sanitized backup state safe to expose to an authenticated administrator. */
public record BackupStatus(
        String status,
        Instant completedAt,
        String failureCode,
        String freshness,
        Long ageSeconds,
        Long maxAgeSeconds) {

    public BackupStatus(String status, Instant completedAt, String failureCode) {
        this(status, completedAt, failureCode, "UNKNOWN", null, null);
    }
}
