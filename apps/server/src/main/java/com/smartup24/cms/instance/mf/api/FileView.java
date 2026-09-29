package com.smartup24.cms.instance.mf.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A file as the API returns it (upload, metadata). The storage bucket and key and the content hash stay on the
 * server: the hash would let a caller test whether a given file exists anywhere in the system.
 */
public record FileView(
        UUID id, String originalName, long sizeBytes, String mimeType, Instant createdAt, Long createdBy) {}
