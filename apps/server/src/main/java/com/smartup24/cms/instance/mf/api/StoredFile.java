package com.smartup24.cms.instance.mf.api;

import java.util.UUID;

/**
 * A stored file as another module keeps a reference to it: its id, the name it came with, the content hash and the
 * size. Server side only, never an API answer (see {@link FileView}); plan 10/10, item 1.3.
 */
public record StoredFile(UUID id, String originalName, String sha256, long sizeBytes) {}
