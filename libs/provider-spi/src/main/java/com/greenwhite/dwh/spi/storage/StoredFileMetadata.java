package com.greenwhite.dwh.spi.storage;

import java.time.Instant;

public record StoredFileMetadata(
        String bucket,
        String key,
        String sha256,
        long sizeBytes,
        String contentType,
        Instant uploadedAt
) {}
