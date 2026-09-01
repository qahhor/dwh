package com.greenwhite.dwh.cp.instance.api;

public record CpHeartbeatResponse(
        boolean accepted,
        long instanceId,
        String licenseStatus,
        String resourceProfile,
        long desiredGeneration) {
}
