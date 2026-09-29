package com.smartup24.cms.instance.mf.api;

import java.time.Instant;
import java.util.UUID;

/** A row of the file list: the file and who uploaded it, without the storage fields (see {@link FileView}). */
public record FileListItem(
        UUID id,
        String originalName,
        long sizeBytes,
        String mimeType,
        Instant createdAt,
        Long createdBy,
        String creatorName,
        String creatorLogin) {}
