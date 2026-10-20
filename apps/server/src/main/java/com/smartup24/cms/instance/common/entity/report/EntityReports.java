package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.report.EntityReportViews.SavedReport;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryAggregateFinish;
import com.smartup24.cms.instance.common.query.QueryAggregateRepository;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryAggregateSpec;
import com.smartup24.cms.instance.common.query.QueryAggregates;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bounded aggregates of a current, authorized root or prepared dataset source (ADR-0032, ADR-0039, ADR-0048). */
@Component
public class EntityReports {
    private final EntityGate gate;
    private final EntityRegistry registry;
    private final EntityScopes scopes;
    private final QueryAggregateRepository aggregates;
    private final ObjectProvider<EntityReportViews> views;
    private final EntityReportDatasets datasets;
    private final EntityReportLabels labels;
    private final Clock clock;
    private final @Nullable ObjectProvider<EntityReportMartRunner> marts;

    public EntityReports(
            EntityGate gate,
            EntityRegistry registry,
            EntityScopes scopes,
            QueryAggregateRepository aggregates,
            ObjectProvider<EntityReportViews> views,
            EntityReportDatasets datasets,
            EntityReportLabels labels,
            ObjectProvider<Clock> clock) {
        this(gate, registry, scopes, aggregates, views, datasets, labels, clock, null);
    }

    @Autowired
    public EntityReports(
            EntityGate gate,
            EntityRegistry registry,
            EntityScopes scopes,
            QueryAggregateRepository aggregates,
            ObjectProvider<EntityReportViews> views,
            EntityReportDatasets datasets,
            EntityReportLabels labels,
            ObjectProvider<Clock> clock,
            @Nullable ObjectProvider<EntityReportMartRunner> marts) {
        this.gate = gate;
        this.registry = registry;
        this.scopes = scopes;
        this.aggregates = aggregates;
        this.views = views;
        this.datasets = datasets;
        this.labels = labels;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.marts = marts;
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
     * What the report builder asks for: the groups, measures and filter, the order of the groups with the top kept, and
     * the analytic shape (ADR-0044).
     *
     * @param sort    the order as a JSON object ({@code {"by":"measure","measure":0,"direction":"desc"}}), or null
     * @param limit   the most groups kept, or null
     * @param pivot   the first grouping makes the rows, the second the columns
     * @param totals  the totals of the groups
     * @param compare the period comparison as a JSON object, or null
     */
    public record Request(
            @Nullable String groupBy,
            @Nullable String measures,
            @Nullable String filter,
            @Nullable String sort,
            @Nullable Integer limit,
            @Nullable Boolean pivot,
            @Nullable Boolean totals,
            @Nullable String compare) {
        /** A flat report: groups, measures and filter, ordered and cut. */
        public Request(
                @Nullable String groupBy,
                @Nullable String measures,
                @Nullable String filter,
                @Nullable String sort,
                @Nullable Integer limit) {
            this(groupBy, measures, filter, sort, limit, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public Execution execute(String code, @Nullable String dataset, Request request) {
        var source = datasets.resolve(code, dataset);
        return run(
                source,
                QueryAggregates.spec(
                        request.groupBy(),
                        request.measures(),
                        request.filter(),
                        request.sort(),
                        request.limit(),
                        request.pivot(),
                        request.totals(),
                        request.compare()));
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
        return executeSaved(code, viewId, extraFilter, null);
    }

    /**
     * A saved report for its reader, with the values of its parameters (ADR-0044) as a JSON object — each missing one
     * takes its default — and a narrowing filter, both added to the saved filter.
     */
    @Transactional(readOnly = true)
    public Execution executeSaved(String code, long viewId, @Nullable String extraFilter, @Nullable String parameters) {
        QueryList base = list(code);
        SavedReport report = Optional.ofNullable(views.getIfAvailable())
                .flatMap(saved -> saved.report(EntityReads.userId(), base.code(), viewId))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.report_not_found"));
        var source = datasets.resolve(code, report.dataset());
        String values = ReportParameters.filter(
                source.list(),
                report.state().get("parameters"),
                ReportFilters.object(parameters),
                QueryAggregateSpec.Anchor.now(clock));
        String filter = ReportFilters.and(ReportFilters.and(report.filter(), values), extraFilter);
        return run(source, QueryAggregateSpec.ofState(report.state(), filter));
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

    private Execution run(EntityReportDatasets.Source source, QueryAggregateSpec spec) {
        QueryAggregate aggregate = QueryAggregates.compile(
                source.list(), spec.withComputed(source.measures()), QueryAggregateSpec.Anchor.now(clock));
        var scope = scopes.listPredicate(source.entity(), aggregate.filter(), EntityReads.userId());
        var params = new LinkedHashMap<>(source.params());
        params.putAll(scope.params());
        var extra = new QueryPlan.SqlFragment(scope.sql() + source.where(), params);
        EntityReportMartRunner mart = marts != null ? marts.getIfAvailable() : null;
        QueryAggregateResult raw = mart != null && mart.supports(source.entity().code())
                ? mart.aggregate(source.entity().code(), aggregate, extra)
                : aggregates.run(aggregate, extra);
        return new Execution(source.list(), QueryAggregateFinish.finish(aggregate, labels.label(aggregate, raw)));
    }
}
