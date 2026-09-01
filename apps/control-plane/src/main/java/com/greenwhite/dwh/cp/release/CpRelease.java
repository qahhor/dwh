package com.greenwhite.dwh.cp.release;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record CpRelease(
        UUID id,
        String version,
        String sourceCommit,
        String manifestDigest,
        URI manifestLocation,
        String verificationBundleDigest,
        String configSchemaVersion,
        String minimumAgentVersion,
        Set<DeploymentMode> deploymentModes,
        ReleaseStatus status,
        List<ReleaseComponent> components,
        Instant createdAt) {

    public CpRelease {
        deploymentModes = Set.copyOf(deploymentModes);
        components = List.copyOf(components);
    }

    public CpRelease withStatus(ReleaseStatus newStatus) {
        return new CpRelease(
                id,
                version,
                sourceCommit,
                manifestDigest,
                manifestLocation,
                verificationBundleDigest,
                configSchemaVersion,
                minimumAgentVersion,
                deploymentModes,
                newStatus,
                components,
                createdAt);
    }
}
