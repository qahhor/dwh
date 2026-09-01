package com.greenwhite.dwh.cp.deployment;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record CpTarget(
        long instanceId,
        long generation,
        UUID releaseId,
        String releaseVersion,
        String manifestDigest,
        URI manifestLocation,
        String configVersion,
        RolloutRing ring,
        MaintenanceWindow maintenanceWindow,
        long requestedBy,
        Instant requestedAt,
        UUID currentReleaseId,
        String currentConfigVersion,
        long currentGeneration) {
}
