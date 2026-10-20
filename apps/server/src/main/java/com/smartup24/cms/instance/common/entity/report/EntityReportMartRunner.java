package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryPlan;

/**
 * Executes validated report aggregates for warehouse mart sources ({@code mart.*}, ADR-0048; Constructor Stage 3,
 * step 3.3) against {@code pg-dwh} while keeping {@code common} independent of {@code warehouse} and {@code upl}.
 */
public interface EntityReportMartRunner {

    /** Prefix of every warehouse mart entity/list code. */
    String MART_PREFIX = "mart.";

    /** Whether {@code entityCode} is a warehouse mart source handled by this runner. */
    boolean supports(String entityCode);

    /** Runs {@code aggregate} for {@code entityCode} with the viewer's {@code extra} scope predicate. */
    QueryAggregateResult aggregate(String entityCode, QueryAggregate aggregate, QueryPlan.SqlFragment extra);
}
