package com.greenwhite.dwh.cp.deployment;

import java.time.Instant;
import java.util.UUID;

public record CpDeployment(
        UUID id,
        long instanceId,
        UUID releaseId,
        long generation,
        UUID previousReleaseId,
        String runnerIdentity,
        CpDeploymentStatus status,
        String reasonCode,
        String technicalLogReference,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {
}
