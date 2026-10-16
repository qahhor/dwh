package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryList;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Current dashboard preferences resolve a report using the reader's rights and observed revision (ADR-0036). */
public interface EntityDashboardReports {
    Result result(
            long dashboardId, long expectedRevision, long viewId, @Nullable LocalDate from, @Nullable LocalDate to);

    record Result(String entity, QueryList list, QueryAggregateResult data) {}
}
