package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reports of an entity's list (ADR-0032, 10.2; plan 10/10, item 5.8): a grouped query built from the entity's
 * list declaration only ({@link QueryAggregates}), read with the same rules as the list — the entity answers only a
 * viewer who may see it (the same 404 otherwise, {@link EntityGate}), the data scope and the archive go into the same
 * SQL ({@link EntityScopes#listPredicate}) and a field the viewer may not see is no field of the report (422, as in the
 * filter). So a total never counts a record or a value the viewer could not read in the list itself.
 */
@Component
public class EntityReports {

    private final EntityGate gate;
    private final EntityRegistry registry;
    private final EntityReads reads;
    private final EntityScopes scopes;
    private final QueryAggregateRepository aggregates;
    /** Read when a saved report runs: the md module's views need these reports to list the dashboard's widgets. */
    private final Supplier<@Nullable EntityReportViews> views;

    @Autowired
    public EntityReports(
            EntityGate gate,
            EntityRegistry registry,
            EntityReads reads,
            EntityScopes scopes,
            QueryAggregateRepository aggregates,
            ObjectProvider<EntityReportViews> views) {
        this(gate, registry, reads, scopes, aggregates, views::getIfAvailable);
    }

    public EntityReports(
            EntityGate gate,
            EntityRegistry registry,
            EntityReads reads,
            EntityScopes scopes,
            QueryAggregateRepository aggregates,
            Supplier<@Nullable EntityReportViews> views) {
        this.gate = gate;
        this.registry = registry;
        this.reads = reads;
        this.scopes = scopes;
        this.aggregates = aggregates;
        this.views = views;
    }

    /** A report asked for in the request: what the report builder shows before it is saved. */
    @Transactional(readOnly = true)
    public QueryAggregateResult run(
            String code, @Nullable String groupBy, @Nullable String measures, @Nullable String filter) {
        EntityDefinition entity = gate.viewable(code);
        return run(entity, QueryAggregates.compile(reads.list(entity), groupBy, measures, filter));
    }

    /**
     * A report or widget the viewer saved on the entity's list, run now: a field that has since become restricted for
     * the viewer fails it with the same 422 as a request would.
     *
     * @throws ApiException 404 {@code error.common.report_not_found} when the viewer has no such report on the list
     */
    @Transactional(readOnly = true)
    public QueryAggregateResult saved(String code, long viewId) {
        EntityDefinition entity = gate.viewable(code);
        QueryList list = reads.list(entity);
        SavedReport report = Optional.ofNullable(views.get())
                .flatMap(saved -> saved.report(EntityReads.userId(), list.code(), viewId))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.report_not_found"));
        return run(entity, QueryAggregates.compile(list, report.groupBy(), report.measures(), report.filter()));
    }

    /** The code of the entity whose list is {@code listCode}, when the viewer may see the entity. */
    public Optional<String> entityOfList(String listCode) {
        return registry.all().stream()
                .filter(entity -> entity.model() != null && listCode.equals(entity.listCode()))
                .findFirst()
                .flatMap(entity -> gate.findViewable(entity.code()))
                .map(EntityDefinition::code);
    }

    /** The entity's list as the viewer reads it: what a report over it may group and measure. */
    public QueryList list(String entityCode) {
        return reads.list(gate.viewable(Objects.requireNonNull(entityCode, "entityCode")));
    }

    private QueryAggregateResult run(EntityDefinition entity, QueryAggregate aggregate) {
        return aggregates.run(aggregate, scopes.listPredicate(entity, aggregate.filter(), EntityReads.userId()));
    }
}
