package com.smartup24.cms.instance.upl.mart;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.report.EntityReportMartRunner;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.upl.mart.UplMartSources.ResolvedSource;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoads;
import com.smartup24.cms.instance.warehouse.api.WarehouseMarts;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs report builder aggregations over {@code mart.<slug>} entities against {@code pg-dwh} (plan 10/10, Constructor
 * stage 3.3).
 */
@Component
public class UplMartReportRunner implements EntityReportMartRunner {

    private final UplMartSources sources;
    private final UplMartBuilder builder;
    private final WarehouseLoads loads;
    private final WarehouseMarts marts;

    public UplMartReportRunner(
            UplMartSources sources, UplMartBuilder builder, WarehouseLoads loads, WarehouseMarts marts) {
        this.sources = sources;
        this.builder = builder;
        this.loads = loads;
        this.marts = marts;
    }

    @Override
    public boolean supports(String entityCode) {
        return entityCode != null && entityCode.startsWith(UplMartSources.PREFIX);
    }

    @Override
    public QueryAggregateResult aggregate(String entityCode, QueryAggregate aggregate, QueryPlan.SqlFragment extra) {
        ResolvedSource resolved = sources.findByMartCode(entityCode)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.report_not_found"));
        String sourceCode = resolved.source().code();
        List<Long> activeLoads = loads.appliedLoadIds(sourceCode);
        if (!activeLoads.isEmpty()) {
            builder.ensureMaterialized(resolved.source(), resolved.version(), activeLoads);
        }
        return marts.aggregate(sourceCode, activeLoads, aggregate, extra);
    }
}
