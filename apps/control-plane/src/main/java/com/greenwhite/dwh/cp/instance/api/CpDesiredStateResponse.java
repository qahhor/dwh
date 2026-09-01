package com.greenwhite.dwh.cp.instance.api;

import com.greenwhite.dwh.cp.deployment.MaintenanceWindow;

import java.net.URI;
import java.util.UUID;

public record CpDesiredStateResponse(
        long generation,
        UUID releaseId,
        String releaseVersion,
        String manifestDigest,
        URI manifestLocation,
        String configVersion,
        MaintenanceWindow maintenanceWindow,
        AllowedAction allowedAction) {

    public enum AllowedAction {
        NONE,
        APPLY_RELEASE
    }
}
