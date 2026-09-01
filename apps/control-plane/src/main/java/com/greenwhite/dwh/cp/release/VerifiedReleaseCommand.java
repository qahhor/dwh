package com.greenwhite.dwh.cp.release;

import java.net.URI;
import java.util.List;
import java.util.Set;

public record VerifiedReleaseCommand(
        String version,
        String sourceCommit,
        String manifestDigest,
        URI manifestLocation,
        String verificationBundleDigest,
        String configSchemaVersion,
        String minimumAgentVersion,
        Set<DeploymentMode> deploymentModes,
        List<ReleaseComponent> components) {

    public VerifiedReleaseCommand {
        deploymentModes = deploymentModes != null ? Set.copyOf(deploymentModes) : null;
        components = components != null ? List.copyOf(components) : null;
    }
}
