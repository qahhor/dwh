package com.smartup24.cms.instance.warehouse.api;

import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryPlan;
import java.time.LocalDate;
import java.util.List;

/**
 * Read/write contract over {@code mart.rows} in {@code pg-dwh} (ADR-0048; Constructor Stage 3, step 3.3).
 *
 * <p>Business modules never touch the {@code dwh} data source directly: {@code upl} projects accepted rows of applied
 * loads through {@link #publishLoad}, and the report runtime executes validated aggregate plans through
 * {@link #aggregate}.
 */
public interface WarehouseMarts {

    /**
     * Writes the normalized rows of an applied load into {@code mart.rows} and removes rows of loads of
     * {@code sourceCode} that are no longer in {@code activeLoadIds}.
     */
    void publishLoad(
            String sourceCode,
            long loadId,
            LocalDate periodFrom,
            LocalDate periodTo,
            List<MartRow> rows,
            List<Long> activeLoadIds);

    /**
     * Returns the subset of {@code loadIds} that already have rows in {@code mart.rows} for {@code sourceCode}.
     */
    List<Long> materializedLoadIds(String sourceCode, List<Long> loadIds);

    /**
     * Runs a validated aggregate query on {@code pg-dwh} scoped to {@code sourceCode} and {@code activeLoadIds}.
     */
    QueryAggregateResult aggregate(
            String sourceCode, List<Long> activeLoadIds, QueryAggregate aggregate, QueryPlan.SqlFragment extra);
}
