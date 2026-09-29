package com.smartup24.cms.instance.report.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One export in the journal (ADR-0018); the file is there while {@code state} is {@code done}
 * and it has not expired. Where the file lies in the storage stays on the server.
 */
public record ExportItem(
        UUID id,
        String list,
        String state,
        Integer rowsCount,
        boolean truncated,
        String fileName,
        Long sizeBytes,
        String errorCode,
        Instant createdAt,
        Instant finishedAt,
        Instant expiresAt) {}
