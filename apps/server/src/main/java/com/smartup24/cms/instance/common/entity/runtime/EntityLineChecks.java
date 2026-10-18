package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.EntityFieldValues;
import com.smartup24.cms.instance.common.entity.EntityFormulaReads;
import com.smartup24.cms.instance.common.entity.EntityLogic;
import com.smartup24.cms.instance.common.entity.EntityLogicRun;
import com.smartup24.cms.instance.common.entity.EntityReferenceValues;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.FieldValueRules;
import com.smartup24.cms.instance.common.entity.LiveEntities;
import com.smartup24.cms.instance.common.entity.store.EntityCollectionStore.Row;
import com.smartup24.cms.instance.common.formula.FormulaFailure;
import com.smartup24.cms.instance.common.formula.FormulaScope;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The checks of a document's rows beyond the rules of their fields (ADR-0043): the computed columns of a live form,
 * evaluated per row by their formulas over the row and the header's entered fields, and the values that need the
 * database — an item of an enumeration and a reference to a row of another entity — one statement per field for all
 * the rows a save sends.
 */
@Component
public class EntityLineChecks {

    private final EntityEnums enums;
    private final EntitySaveChecks checks;
    private final ObjectProvider<LiveEntities> live;
    private final Clock clock;

    public EntityLineChecks(EntityEnums enums, EntitySaveChecks checks, ObjectProvider<LiveEntities> live) {
        this.enums = enums;
        this.checks = checks;
        this.live = live;
        this.clock = Clock.systemUTC();
    }

    /** The form logic of the entity: the formulas of its rows' computed columns (ADR-0043); none without it. */
    EntityLogic logic(EntityDefinition entity) {
        LiveEntities served = live.getIfAvailable();
        return served == null ? EntityLogic.NONE : served.logic(entity.code());
    }

    /**
     * Evaluates the computed columns of every row in their order, over the row and the header's entered fields: a value
     * is kept as the column keeps it, a formula that cannot give one is a problem of its row's column.
     */
    void compute(
            EntityDefinition entity,
            EntityCollection collection,
            List<Row> rows,
            Map<String, ?> record,
            List<FieldErrorItem> errors) {
        List<EntityLogic.Computed> computed = logic(entity).lineComputed(collection.key());
        if (computed.isEmpty()) return;
        EntityDefinition line = collection.line(entity.code());
        FormulaScope scope = FormulaScope.current(clock);
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            Map<String, @Nullable Object> values = new LinkedHashMap<>(row.values());
            for (EntityLogic.Computed column : computed) {
                FormField field = line.fieldsByKey().get(column.field());
                if (field == null) continue;
                try {
                    Object value = column.formula().evaluate(EntityFormulaReads.row(values, record), scope);
                    values.put(column.field(), EntityLogicRun.stored(field, value));
                } catch (FormulaFailure failure) {
                    errors.add(failure.fieldError(collection.key() + "[" + index + "]." + column.field()));
                }
            }
            rows.set(index, new Row(row.id(), values));
        }
    }

    /**
     * The checks of the rows against the database, one statement per field for all rows: an item of an enumeration
     * exists and, when new to the row, is in use; a reference new to the row names a row of its target the saver sees,
     * in use. A value equal to the one the row had is kept.
     */
    void lookups(
            EntityCollection collection,
            List<Row> rows,
            List<Map<String, Object>> before,
            long userId,
            List<FieldErrorItem> errors) {
        Map<Long, Map<String, Object>> was = new LinkedHashMap<>();
        before.forEach(row -> was.put(EntityReads.id(row), row));
        for (EntityField field : collection.fields()) {
            FormField form = field.formField();
            if (form == null) continue;
            Map<Integer, Object> fresh = new LinkedHashMap<>();
            for (int index = 0; index < rows.size(); index++) {
                Row row = rows.get(index);
                Object value = row.values().get(field.key());
                Map<String, Object> old = row.id() == null ? Map.of() : was.getOrDefault(row.id(), Map.of());
                if (value != null && !FieldValueRules.same(form, value, old.get(field.key()))) fresh.put(index, value);
            }
            if (fresh.isEmpty()) continue;
            if (field.type() == FieldType.ENUM) {
                enumProblems(collection, form, fresh, errors);
            } else if (field.options().target() != null) {
                referenceProblems(collection, field, fresh, userId, errors);
            }
        }
    }

    private void enumProblems(
            EntityCollection collection, FormField field, Map<Integer, Object> fresh, List<FieldErrorItem> errors) {
        EntityEnums.Items items =
                enums.all(Objects.requireNonNull(field.params().enumeration(), field.key()));
        fresh.forEach((index, value) -> {
            String at = collection.key() + "[" + index + "]." + field.key();
            String code = String.valueOf(value);
            if (!items.names().containsKey(code)) {
                errors.add(FieldErrorItem.keyed(at, EntityValidator.INVALID, "error.field.option_required"));
            } else if (items.archived().contains(code)) {
                errors.add(FieldErrorItem.keyed(at, EntityFieldValues.ARCHIVED, "error.field.ref_archived"));
            }
        });
    }

    private void referenceProblems(
            EntityCollection collection,
            EntityField field,
            Map<Integer, Object> fresh,
            long userId,
            List<FieldErrorItem> errors) {
        Map<Integer, Long> keys = new LinkedHashMap<>();
        fresh.forEach((index, value) ->
                keys.put(index, EntityReferenceValues.key(value).orElse(-1L)));
        Map<Long, Boolean> visible = checks.visibleRows(
                Objects.requireNonNull(field.options().target()), new HashSet<>(keys.values()), userId);
        keys.forEach((index, key) -> {
            String at = collection.key() + "[" + index + "]." + field.key();
            Boolean archived = visible.get(key);
            if (archived == null) {
                errors.add(FieldErrorItem.keyed(at, EntitySaveChecks.NOT_FOUND, "error.field.ref_not_found"));
            } else if (archived) {
                errors.add(FieldErrorItem.keyed(at, EntityFieldValues.ARCHIVED, "error.field.ref_archived"));
            }
        });
    }
}
