package com.smartup24.cms.instance.report.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An import as its owner follows it (ADR-0032, 10.1): the state of its job, the rows of the file and how many are done,
 * created, updated and refused, the first problems by row, and whether the report — the file with a column of problems
 * — can be downloaded. A failure of the whole file is named by {@code errorCode}.
 */
public record ImportView(
        UUID id,
        String entity,
        String mode,
        String state,
        Integer rowsTotal,
        int rowsDone,
        int created,
        int updated,
        int failed,
        String errorCode,
        List<ImportRowError> errors,
        boolean report,
        Instant createdAt,
        Instant finishedAt,
        Instant expiresAt) {}
