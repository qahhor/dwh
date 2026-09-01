package com.greenwhite.dwh.cp.instance.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CpHeartbeatRequest(
        @NotBlank @Size(max = 64) String appVersion,
        @NotBlank @Size(max = 32) String schemaVersion,
        @Size(max = 64) String releaseVersion,
        @Size(max = 64) String configVersion,
        @Valid ComponentHealth components,
        @Valid StorageTelemetry storage,
        @Valid BackupTelemetry backup,
        @Valid AgentTelemetry agents,
        @Pattern(regexp = "IDLE|REQUESTED|PREFLIGHT|PREFLIGHT_FAILED|BACKUP_VERIFIED|BACKUP_FAILED|MIGRATING|DEPLOYING|VERIFYING|SUCCEEDED|ROLLING_BACK|ROLLED_BACK|RECOVERY_REQUIRED|CANCELLED")
        String deploymentState,
        @Valid CapacityTelemetry capacity) {

    public record ComponentHealth(
            Health app,
            Health database,
            Health typesense,
            Health objectStorage) {
    }

    public record StorageTelemetry(
            @PositiveOrZero long usedBytes,
            @PositiveOrZero long quotaBytes) {
    }

    public record BackupTelemetry(
            Instant lastCompletedAt,
            BackupStatus status) {
    }

    public record AgentTelemetry(
            Health tunnel,
            Health telemetry) {
    }

    public record CapacityTelemetry(
            @PositiveOrZero long activeUsers,
            @PositiveOrZero long outboxPending,
            @PositiveOrZero long outboxDeadLetter) {
    }

    public enum Health {
        UP,
        DEGRADED,
        DOWN,
        UNKNOWN
    }

    public enum BackupStatus {
        UNKNOWN,
        UPLOADED,
        VERIFIED,
        FAILED
    }
}
