package com.greenwhite.dwh.cp.instance.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public record CpBackupReportRequest(
        @NotNull UUID backupId,
        @NotNull ArtifactStatus status,
        @Pattern(regexp = "[0-9a-f]{64}") String checksumSha256,
        @Min(0) @Max(86_400) Integer durationSec,
        @NotNull @PastOrPresent Instant completedAt,
        @Pattern(regexp = "[a-z0-9_]{1,64}") String reasonCode) {

    public enum ArtifactStatus {
        UPLOADED,
        FAILED
    }
}
