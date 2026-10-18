package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.EntityListFields;
import com.smartup24.cms.instance.common.entity.report.EntityReportDatasetStore.Definition;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The source of a report over an administrator's dataset (ADR-0044): the entity's list — or, at the grain of a table of
 * its document lines (ADR-0043), each line joined to its record, the line's fields named {@code line<Key>} — narrowed to
 * the fields the dataset offers and to its fixed filter. Everything is built from the registry and the declarations;
 * the filter is the list's own DSL, compiled with its values bound as parameters. The reader's scope is applied to the
 * record as for its list, and the reader's field rights to each field, so a dataset never shows more than the list.
 */
@Component
public class EntityReportAdminSources {
    /** The prefix of a line's field: {@code product} of a line is {@code lineProduct}. */
    public static final String LINE = "line";

    /** What a line holds that a report can read: a plain value, a choice or a reference. */
    private static final Set<FieldType> LINE_TYPES = Set.of(
            FieldType.TEXT,
            FieldType.NUMBER,
            FieldType.DATE,
            FieldType.DATETIME,
            FieldType.BOOLEAN,
            FieldType.SELECT,
            FieldType.ENUM,
            FieldType.REF);

    private final EntityReads reads;
    private final EntityEnums enums;

    public EntityReportAdminSources(EntityReads reads, EntityEnums enums) {
        this.reads = reads;
        this.enums = enums;
    }

    /**
     * Every field a dataset over the entity may offer: the list's, and with {@code lines} each field of a line.
     *
     * @throws ApiException 404 when the entity has no such table of lines
     */
    public QueryList full(EntityDefinition root, @Nullable String lines) {
        QueryList base = reads.list(root);
        if (lines == null) return base;
        EntityModel model = Objects.requireNonNull(root.model(), root.code());
        EntityCollection collection = model.collections().stream()
                .filter(candidate -> candidate.key().equals(lines))
                .findFirst()
                .orElseThrow(EntityReportAdminSources::missing);
        String alias = collection.alias().equals(model.alias()) ? collection.alias() + "_l" : collection.alias();
        List<QueryField> fields = new ArrayList<>(base.fields());
        Set<String> taken = new HashSet<>();
        base.fields().forEach(field -> taken.add(field.key()));
        for (EntityField field : collection.fields()) {
            Optional<QueryField> line = line(field, alias);
            if (line.isPresent() && taken.add(line.get().key())) fields.add(line.get());
        }
        String from = base.from() + " join " + collection.table() + " " + alias + " on " + alias + "."
                + collection.parentColumn() + " = " + model.alias() + ".id";
        return copy(base, from, alias + ".id", fields);
    }

    /** The source of the dataset for the current reader: its fields, its fixed filter and its computed measures. */
    EntityReportDatasets.Source source(EntityDefinition root, Definition dataset) {
        QueryList full = full(root, dataset.lines());
        QueryPlan.SqlFragment fixed;
        try {
            fixed = prefixed(QueryCompiler.compile(full, dataset.filter(), null, null, null)
                    .where());
        } catch (ApiException hidden) {
            if (hidden.getErrorCode() != ErrorCode.VALIDATION_FAILED) throw hidden;
            // A fixed filter over a field the reader may not see: the dataset is not theirs to read.
            throw missing();
        }
        Map<String, QueryField> byKey = new LinkedHashMap<>();
        full.fields().forEach(field -> byKey.put(field.key(), field));
        List<QueryField> offered = new ArrayList<>();
        for (String key : dataset.fields()) {
            QueryField field = byKey.get(key);
            if (field != null) offered.add(field);
        }
        QueryField sort = byKey.get(full.defaultSort());
        if (sort != null && offered.stream().noneMatch(field -> field.key().equals(sort.key()))) offered.add(sort);
        QueryList list = copy(full, full.from(), full.idSql(), offered);
        return new EntityReportDatasets.Source(root, list, fixed.params(), fixed.sql(), dataset.measures());
    }

    /**
     * The problems of a dataset's fields and filter for its administrator: unknown or repeated fields, a number of the
     * record at the grain of lines — summed once per line it would count the record several times — and a filter the
     * list does not take; each at the part to fix.
     */
    public List<FieldErrorItem> check(
            QueryList full, @Nullable String lines, List<String> fields, @Nullable String filter) {
        List<FieldErrorItem> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            String key = fields.get(i);
            Optional<QueryField> field = full.viewerField(key);
            String at = "fields[" + i + "]";
            if (field.isEmpty() || !seen.add(key)) {
                errors.add(FieldErrorItem.keyed(at, "REPORT_DATASET_INVALID", "error.md.report_dataset_field"));
            } else if (lines != null && !key.startsWith(LINE) && headerNumber(field.get())) {
                errors.add(FieldErrorItem.keyed(at, "REPORT_DATASET_INVALID", "error.md.report_dataset_field_grain"));
            }
        }
        try {
            QueryCompiler.compile(full, filter, null, null, null);
        } catch (ApiException failure) {
            List<FieldErrorItem> found = failure.getFieldErrors();
            if (found == null) throw failure;
            found.forEach(item -> errors.add(item));
        }
        return errors;
    }

    private static boolean headerNumber(QueryField field) {
        return field.type() == QueryFieldType.NUMBER && field.ref() == null;
    }

    /** The list field of a line's field over the line's alias, when a report can read it. */
    private Optional<QueryField> line(EntityField field, String alias) {
        if (!LINE_TYPES.contains(field.type()) || field.key().length() > 60) return Optional.empty();
        QueryFieldType type = EntityListFields.listType(field.type());
        boolean enumeration = type == QueryFieldType.ENUM;
        String key = LINE
                + field.key().substring(0, 1).toUpperCase(Locale.ROOT)
                + field.key().substring(1);
        QueryField listed = new QueryField(
                key,
                field.labelKey(),
                type,
                field.sql(alias),
                true,
                false,
                true,
                true,
                enumeration ? field.options().options() : List.of(),
                enumeration ? field.options().optionLabelPrefix() : null,
                false,
                field.access().requiredForm(),
                field.access().requiredAction(),
                field.label(),
                null,
                field.options().ref(),
                field.type().formatted() ? field.type().wire() : null,
                null);
        String reference = field.options().enumeration();
        return Optional.of(
                reference != null && QueryField.ENUM_FORMAT.equals(listed.format())
                        ? listed.withEnumeration(enums.items(reference))
                        : listed);
    }

    private static QueryList copy(QueryList base, String from, String idSql, List<QueryField> fields) {
        return new QueryList(
                base.code(),
                base.form(),
                base.action(),
                base.select(),
                from,
                idSql,
                fields,
                base.defaultSort(),
                base.defaultDescending(),
                base.defaultLimit(),
                base.maxLimit(),
                base.customEntity(),
                base.attributesSql(),
                base.estimatedTotal());
    }

    /** The fixed filter with parameters of its own, so they never meet the report's ({@code q_} becomes {@code ds_q_}). */
    private static QueryPlan.SqlFragment prefixed(QueryPlan.SqlFragment where) {
        String sql = where.sql().replace(":q_", ":ds_q_");
        Map<String, Object> params = new LinkedHashMap<>();
        where.params().forEach((name, value) -> params.put("ds_" + name, value));
        return new QueryPlan.SqlFragment(sql, params);
    }

    static ApiException missing() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.report_not_found");
    }
}
