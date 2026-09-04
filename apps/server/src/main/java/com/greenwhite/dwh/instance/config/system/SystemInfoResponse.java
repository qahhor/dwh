package com.greenwhite.dwh.instance.config.system;

import java.time.Instant;
import java.util.Map;

/** Non-secret operational summary for the local SmartupCMS administrator. */
public record SystemInfoResponse(
        String appVersion,
        String schemaVersion,
        Organization organization,
        String storageProvider,
        Map<String, Component> components,
        BackupStatus backup,
        Instant checkedAt
) {
    public SystemInfoResponse {
        components = Map.copyOf(components);
    }

    public record Organization(String code, String name, String resourceProfile) {
    }

    public record Component(String status) {
    }
}
