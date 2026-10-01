package com.smartup24.cms.instance.fnd.api;

import java.time.Instant;
import java.time.LocalDate;

/** A row of a versions table that follows the foundation's versioning standard. */
public record FndVersion(
        long headerId,
        int version,
        LocalDate validFrom,
        LocalDate validTo,
        String status,
        Instant publishedAt,
        String publishedBy,
        int lockVersion) {

    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";
    public static final String SUPERSEDED = "superseded";
}
