package com.smartup24.cms.instance.kauth.api;

import java.time.Instant;

/** A personal API token as its owner sees it: the prefix only, never the token or its hash. */
public record ApiTokenView(
        Long id,
        Long userId,
        String name,
        String tokenPrefix,
        Instant expiresAt,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt) {}
