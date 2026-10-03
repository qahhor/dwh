package com.smartup24.cms.spi.storage;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.time.Instant;

@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record StoredFileMetadata(
        String bucket, String key, String sha256, long sizeBytes, String contentType, Instant uploadedAt) {}
