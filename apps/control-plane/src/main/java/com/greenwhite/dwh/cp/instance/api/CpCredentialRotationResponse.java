package com.greenwhite.dwh.cp.instance.api;

import java.time.Instant;

public record CpCredentialRotationResponse(
        long instanceId,
        String credential,
        Instant previousValidUntil) {
}
