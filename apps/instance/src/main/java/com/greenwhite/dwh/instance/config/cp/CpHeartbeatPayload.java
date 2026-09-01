package com.greenwhite.dwh.instance.config.cp;

import java.time.Instant;

public record CpHeartbeatPayload(
        String appVersion,
        String schemaVersion,
        String releaseVersion,
        String configVersion,
        ComponentHealth components,
        StorageTelemetry storage,
        BackupTelemetry backup,
        AgentTelemetry agents,
        String deploymentState,
        CapacityTelemetry capacity) {

    public record ComponentHealth(
            String app,
            String database,
            String typesense,
            String objectStorage) {
    }

    public record StorageTelemetry(
            long usedBytes,
            long quotaBytes) {
    }

    public record BackupTelemetry(
            Instant lastCompletedAt,
            String status) {
    }

    public record AgentTelemetry(
            String tunnel,
            String telemetry) {
    }

    public record CapacityTelemetry(
            long activeUsers,
            long outboxPending,
            long outboxDeadLetter) {
    }
}
