package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityListFields;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.api.ReportDatasetDtos;
import com.smartup24.cms.instance.common.entity.api.ReportDatasetDtos.Catalog;
import com.smartup24.cms.instance.common.entity.api.ReportDatasetDtos.Item;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryComputedMeasure;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.web.RequestLanguage;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Current root, target and field access share one source resolver in every report path (ADR-0039). */
@Component
public class EntityReportDatasets {
    /** The title key of an administrator's dataset in the catalog; its own name goes beside it. */
    public static final String ADMIN_TITLE = "report.dataset.custom";

    private final Map<String, EntityReportDataset> declarations;
    private final EntityGate gate;
    private final EntityReads reads;
    private final EntityScopes scopes;
    private final @Nullable ObjectProvider<EntityReportDatasetStore> store;
    private final @Nullable EntityReportAdminSources admin;

    /** The datasets declared in code only: no administrator's datasets. */
    public EntityReportDatasets(
            List<EntityReportDataset> datasets,
            List<EntityDefinition> entities,
            EntityGate gate,
            EntityReads reads,
            EntityScopes scopes) {
        this(datasets, entities, gate, reads, scopes, null, null);
    }

    @Autowired
    public EntityReportDatasets(
            List<EntityReportDataset> datasets,
            List<EntityDefinition> entities,
            EntityGate gate,
            EntityReads reads,
            EntityScopes scopes,
            @Nullable ObjectProvider<EntityReportDatasetStore> store,
            @Nullable EntityReportAdminSources admin) {
        var definitions = new LinkedHashMap<String, EntityDefinition>();
        entities.forEach(entity -> definitions.put(entity.code(), entity));
        var declared = new LinkedHashMap<String, EntityReportDataset>();
        var counts = new LinkedHashMap<String, Integer>();
        for (var dataset : datasets) {
            if (counts.merge(dataset.rootEntity(), 1, Integer::sum) > ReportDatasetDtos.MAX_ITEMS)
                throw new IllegalArgumentException("Too many report datasets for one entity");
            if (declared.put(dataset.code(), dataset) != null)
                throw new IllegalArgumentException("Duplicate report dataset");
            EntityReportDatasetRules.validate(dataset, definitions);
        }
        declarations = java.util.Collections.unmodifiableMap(declared);
        this.gate = gate;
        this.reads = reads;
        this.scopes = scopes;
        this.store = store;
        this.admin = admin;
    }

    /**
     * A report's source: its entity, its list, and what narrows it beyond the reader's scope — the parameters and the
     * SQL of an administrator's fixed filter ({@code " and ..."}) — with the measures computed over its aggregates.
     */
    public record Source(
            EntityDefinition entity,
            QueryList list,
            Map<String, Object> params,
            String where,
            List<QueryComputedMeasure> measures) {
        public Source(EntityDefinition entity, QueryList list, Map<String, Object> params) {
            this(entity, list, params, "", List.of());
        }

        public Source {
            params = Map.copyOf(params);
            measures = List.copyOf(measures);
        }
    }

    public Catalog catalog(String code) {
        gate.viewable(code);
        var items = new ArrayList<Item>();
        for (var dataset : declarations.values()) {
            if (!dataset.rootEntity().equals(code)) continue;
            try {
                resolve(code, dataset.code());
                items.add(new Item(dataset.code(), dataset.titleKey(), null, List.of()));
            } catch (ApiException denied) {
                if (denied.getErrorCode() != ErrorCode.NOT_FOUND) throw denied;
            }
        }
        EntityReportDatasetStore datasets = store == null ? null : store.getIfAvailable();
        if (datasets != null) {
            String language = RequestLanguage.current();
            for (var dataset : datasets.over(code)) {
                if (items.size() >= ReportDatasetDtos.MAX_ITEMS) break;
                String full = EntityReportDatasetStore.PREFIX + dataset.code();
                try {
                    resolve(code, full);
                    items.add(new Item(
                            full,
                            ADMIN_TITLE,
                            dataset.names().getOrDefault(language, dataset.code()),
                            dataset.measures().stream()
                                    .map(measure -> new ReportDatasetDtos.ItemMeasure(
                                            measure.key(), measure.labels().getOrDefault(language, measure.key())))
                                    .toList()));
                } catch (ApiException denied) {
                    if (denied.getErrorCode() != ErrorCode.NOT_FOUND) throw denied;
                }
            }
        }
        return new Catalog(List.copyOf(items));
    }

    public Source resolve(String code, @Nullable String datasetCode) {
        EntityDefinition root = gate.viewable(code);
        QueryList base = reads.list(root);
        if (datasetCode == null) return new Source(root, base, Map.of());
        if (datasetCode.startsWith(EntityReportDatasetStore.PREFIX)) return adminSource(root, datasetCode);
        EntityReportDataset dataset = declarations.get(datasetCode);
        if (dataset == null || !dataset.rootEntity().equals(code)) throw missing();
        EntityField reference = EntityReportDatasetRules.reference(dataset, root);
        if (!EntityFieldRights.visible(reference.access())) throw missing();
        EntityDefinition target = gate.findViewable(
                        Objects.requireNonNull(reference.options().target()))
                .orElseThrow(EntityReportDatasets::missing);
        return project(root, base, reference, target, dataset);
    }

    /** An administrator's dataset over {@code root} (ADR-0044); 404 when there is none or the reader may not read it. */
    private Source adminSource(EntityDefinition root, String datasetCode) {
        String code = datasetCode.substring(EntityReportDatasetStore.PREFIX.length());
        EntityReportDatasetStore datasets = store == null ? null : store.getIfAvailable();
        if (datasets == null
                || admin == null
                || !EntityReportDatasetStore.CODE.matcher(code).matches()) throw missing();
        var dataset = datasets.find(code)
                .filter(found -> found.entity().equals(root.code()))
                .orElseThrow(EntityReportDatasets::missing);
        return admin.source(root, dataset);
    }

    private Source project(
            EntityDefinition root,
            QueryList base,
            EntityField reference,
            EntityDefinition target,
            EntityReportDataset dataset) {
        var rootModel = EntityReportDatasetRules.model(root);
        var targetModel = EntityReportDatasetRules.model(target);
        String alias = rootModel.alias().equals("report_target") ? "report_target_1" : "report_target";
        var fields = new ArrayList<>(base.fields());
        var columns = new ArrayList<String>();
        columns.add(targetModel.alias() + ".id as target_id");
        for (var projection : dataset.projections()) {
            if (base.fields().stream().anyMatch(field -> field.key().equals(projection.key())))
                throw new IllegalStateException("Dataset shadows a current root field");
            EntityField field = EntityReportDatasetRules.field(targetModel, projection.field());
            if (!EntityFieldRights.visible(field.access())) continue;
            String column = "value_" + columns.size();
            columns.add(field.sql(targetModel.alias()) + " as " + column);
            fields.add(projected(projection, field, alias + "." + column));
        }
        var scope = scopes.rows(target, EntityReads.userId());
        String active = target.capabilities().contains(EntityCapability.ARCHIVE)
                ? " and " + targetModel.alias() + ".archived_at is null"
                : "";
        String from = base.from() + " left join (select " + String.join(", ", columns) + " from "
                + targetModel.from() + " where 1=1" + scope.sql() + active + ") " + alias + " on "
                + alias + ".target_id = " + reference.sql(rootModel.alias());
        QueryList list = new QueryList(
                base.code(),
                base.form(),
                base.action(),
                base.select(),
                from,
                base.idSql(),
                fields,
                base.defaultSort(),
                base.defaultDescending(),
                base.defaultLimit(),
                base.maxLimit(),
                base.customEntity(),
                base.attributesSql(),
                base.estimatedTotal());
        return new Source(root, list, scope.params());
    }

    private static QueryField projected(EntityReportDataset.Projection projection, EntityField field, String sql) {
        boolean choice = field.type() == FieldType.SELECT;
        return new QueryField(
                projection.key(),
                projection.labelKey() == null ? field.labelKey() : projection.labelKey(),
                EntityListFields.listType(field.type()),
                sql,
                true,
                false,
                true,
                true,
                choice ? field.options().options() : List.of(),
                choice ? field.options().optionLabelPrefix() : null,
                false,
                field.access().requiredForm(),
                field.access().requiredAction(),
                projection.labelKey() == null ? field.label() : null,
                null,
                null,
                field.type().formatted() ? field.type().wire() : null,
                null);
    }

    private static ApiException missing() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.report_not_found");
    }
}
