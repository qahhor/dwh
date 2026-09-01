package com.greenwhite.dwh.cp.instance;

public record CpInstancePrincipal(
        long instanceId,
        long clientId,
        String clientCode,
        long credentialId) {
}
