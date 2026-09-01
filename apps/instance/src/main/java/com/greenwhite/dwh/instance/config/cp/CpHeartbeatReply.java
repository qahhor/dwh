package com.greenwhite.dwh.instance.config.cp;

public record CpHeartbeatReply(
        boolean accepted,
        long instanceId,
        String licenseStatus,
        String resourceProfile,
        long desiredGeneration) {
}
