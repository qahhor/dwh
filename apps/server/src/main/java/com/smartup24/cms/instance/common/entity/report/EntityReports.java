package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.report.EntityReportViews.SavedReport;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryAggregateRepository;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryAggregates;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bounded aggregates of a current, authorized root or prepared dataset source (ADR-0032, ADR-0039). */
@Component
public class EntityReports {
    private final EntityGate gate;
    private final EntityRegistry registry;
    private final EntityScopes scopes;
    private final QueryAggregateRepository aggregates;
    private final ObjectProvider<EntityReportViews> views;
    private final EntityReportDatasets datasets;
    private final EntityReportLabels labels;

    public EntityReports(
            EntityGate gate,
            EntityRegistry registry,
            EntityScopes scopes,
            QueryAggregateRepository aggregates,
            ObjectProvider<EntityReportViews> views,
            EntityReportDatasets datasets,
            EntityReportLabels labels) {
        this.gate = gate;
        this.registry = registry;
        this.scopes = scopes;
        this.aggregates = aggregates;
        this.views = views;
        this.datasets = datasets;
        this.labels = labels;
    }

    /** Internal execution metadata stays with its result until export; controllers return data only. */
    public record Execution(QueryList list, QueryAggregateResult data) {}

    @Transactional(readOnly = true)
    public QueryAggregateResult run(
            String code, @Nullable String groupBy, @Nullable String measures, @Nullable String filter) {
        return execute(code, null, groupBy, measures, filter).data();
    }

    @Transactional(readOnly = true)
    public Execution execute(
            String code,
            @Nullable String dataset,
            @Nullable String groupBy,
            @Nullable String measures,
            @Nullable String filter) {
        return execute(code, dataset, new Request(groupBy, measures, filter, null, null));
    }

    /**
     * What the report builder asks for: the groups, measures and filter, and the order of the groups with the top kept.
     *
     * @param sort  the order as a JSON object ({@code {"by":"measure","measure":0,"direction":"desc"}}), or null
     * @param limit the most groups kept, or null
     */
    public record Request(
            @Nullable String groupBy,
            @Nullable String measures,
            @Nullable String filter,
            @Nullable String sort,
            @Nullable Integer limit) {}

    @Transactional(readOnly = true)
    public Execution execute(String code, @Nullable String dataset, Request request) {
        var source = datasets.resolve(code, dataset);
        return run(
                source,
                QueryAggregates.compile(
                        source.list(),
                        request.groupBy(),
                        request.measures(),
                        request.filter(),
                        request.sort(),
                        request.limit()));
    }

    @Transactional(readOnly = true)
    public QueryAggregateResult saved(String code, long viewId) {
        return executeSaved(code, viewId, null).data();
    }

    @Transactional(readOnly = true)
    public QueryAggregateResult saved(String code, long viewId, @Nullable String extraFilter) {
        return executeSaved(code, viewId, extraFilter).data();
    }

    @Transactional(readOnly = true)
    public Execution executeSaved(String code, long viewId, @Nullable String extraFilter) {
        QueryList base = list(code);
        SavedReport report = Optional.ofNullable(views.getIfAvailable())
                .flatMap(saved -> saved.report(EntityReads.userId(), base.code(), viewId))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.report_not_found"));
        var source = datasets.resolve(code, report.dataset());
        return run(
                source,
                QueryAggregates.compile(
                        source.list(),
                        report.groupBy(),
                        report.measures(),
                        ReportFilters.and(report.filter(), extraFilter),
                        report.sort(),
                        report.limit()));
    }

    public Optional<String> entityOfList(String listCode) {
        return registry.all().stream()
                .filter(entity -> entity.model() != null && listCode.equals(entity.listCode()))
                .findFirst()
                .flatMap(entity -> gate.findViewable(entity.code()))
                .map(EntityDefinition::code);
    }

    public QueryList list(String entityCode) {
        return list(entityCode, null);
    }

    public QueryList list(String entityCode, @Nullable String dataset) {
        return datasets.resolve(entityCode, dataset).list();
    }

    private Execution run(EntityReportDatasets.Source source, QueryAggregate aggregate) {
        var scope = scopes.listPredicate(source.entity(), aggregate.filter(), EntityReads.userId());
        var params = new LinkedHashMap<>(source.params());
        params.putAll(scope.params());
        return new Execution(
                source.list(),
                labels.label(aggregate, aggregates.run(aggregate, new QueryPlan.SqlFragment(scope.sql(), params))));
    }
}
