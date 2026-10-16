package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.report.EntityDashboardReports;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Table, chart and download resolve the same current layout and run the same aggregate query (ADR-0036). */
@Service
public class MdDashboardRunner implements EntityDashboardReports {
    private final MdDashboardService dashboards;
    private final MdDashboardPeriod periods;
    private final EntityReports reports;

    public MdDashboardRunner(MdDashboardService dashboards, MdDashboardPeriod periods, EntityReports reports) {
        this.dashboards = dashboards;
        this.periods = periods;
        this.reports = reports;
    }

    @Override
    @Transactional(readOnly = true)
    public Result result(
            long dashboardId, long expectedRevision, long viewId, @Nullable LocalDate from, @Nullable LocalDate to) {
        long userId = Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
        var widget = dashboards.widget(userId, dashboardId, expectedRevision, viewId);
        var report = Objects.requireNonNull(widget.report());
        String entity = report.entity();
        String extra =
                periods.filter(entity, MdReportViewState.dataset(report.state()), widget.periodField(), from, to);
        var execution = reports.executeSaved(entity, viewId, extra);
        return new Result(entity, execution.list(), execution.data());
    }
}
