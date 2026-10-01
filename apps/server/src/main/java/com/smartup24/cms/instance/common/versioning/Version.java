package com.smartup24.cms.instance.common.versioning;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** A row of a versions table that follows the versioning standard (V102). */
public record Version(
        long headerId,
        int version,
        LocalDate validFrom,
        @Nullable LocalDate validTo,
        String status,
        @Nullable Instant publishedAt,
        @Nullable String publishedBy,
        int lockVersion) {

    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";
    public static final String SUPERSEDED = "superseded";
}
