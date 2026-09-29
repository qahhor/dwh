package com.smartup24.cms.instance.ms.task.api;

import java.time.Instant;
import java.util.UUID;

/** A file attached to a task; where the file is stored does not leave the server. */
public record TaskFileView(UUID fileId, String fileName, long sizeBytes, String mimeType, Instant createdAt) {}
