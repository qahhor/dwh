package com.smartup24.cms.instance.fnd.load;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A load version: its {@code id} is the single {@code load_id} that tags the {@code raw} rows in pg-dwh and the
 * cache generations.
 */
public record FndLoad(
        long id,
        String sourceCode,
        UUID packageRef,
        LocalDate periodFrom,
        LocalDate periodTo,
        String formatVersion,
        Instant appliedAt,
        String appliedBy,
        Integer rowsTotal,
        Integer rowsAccepted,
        Integer rowsRejected,
        String status,
        Long supersededBy) {

    public static final String PENDING = "pending";
    public static final String APPLIED = "applied";
    public static final String FAILED = "failed";
    public static final String SUPERSEDED = "superseded";
}
