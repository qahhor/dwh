package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaFailure;
import com.smartup24.cms.instance.common.formula.FormulaScope;
import com.smartup24.cms.instance.common.formula.FormulaValues;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The logic of one entity applied to one save (ADR-0042), in a fixed order the web repeats: the computed fields are
 * never taken from the body; a saved record's field read-only by its formula over the stored record keeps its value;
 * a new record's empty fields take their default formulas, in declaration order; the computed fields are evaluated in
 * their order; the fields whose condition is not true are hidden and lose their values; the computed fields are
 * evaluated again over what is left; then an optional field is required while its condition is true. A formula that
 * cannot give a value answers a problem of its field, never a server error.
 */
public final class EntityLogicRun {
    private final EntityDefinition entity;
    private final EntityLogic logic;
    private final FormulaScope scope;

    public EntityLogicRun(EntityDefinition entity, EntityLogic logic, FormulaScope scope) {
        this.entity = entity;
        this.logic = logic;
        this.scope = scope;
    }

    /** The values of a body without the computed fields: the server writes them. */
    public Map<String, Object> unsent(Map<String, ?> values) {
        Map<String, Object> kept = new LinkedHashMap<>(values);
        kept.keySet().removeAll(logic.computedKeys());
        return kept;
    }

    /**
     * The fields of a saved record that its formulas keep read-only, tested over the record as it is stored; a value of
     * one of them that differs from the stored value is a {@code readonly} problem, an equal one is passed over — a
     * client may send the whole record.
     */
    public Set<String> readonlyProblems(Map<String, ?> sent, Map<String, ?> before, List<FieldErrorItem> errors) {
        Set<String> kept = holding(logic.readonly(), before, errors);
        for (String key : kept) {
            FormField field = entity.fieldsByKey().get(key);
            if (field == null || !sent.containsKey(key)) continue;
            if (!FieldValueRules.same(field, sent.get(key), before.get(key))) {
                errors.add(FieldErrorItem.keyed(key, EntityValidator.READONLY, "error.field.readonly"));
            }
        }
        return kept;
    }

    /** The fields its conditions hide over the record as it will be. */
    public Set<String> hidden(Map<String, ?> record, List<FieldErrorItem> errors) {
        Set<String> shown = holding(logic.visible(), record, errors);
        Set<String> hidden = new LinkedHashSet<>(logic.visible().keySet());
        hidden.removeAll(shown);
        return hidden;
    }

    /** The optional fields its conditions make required over the record as it will be. */
    public Set<String> required(Map<String, ?> record, List<FieldErrorItem> errors) {
        return holding(logic.required(), record, errors);
    }

    /**
     * Puts the default of each empty field of a new record into {@code prepared} and {@code record}, in declaration
     * order: a default formula sees the values sent and the defaults before it.
     */
    public void defaults(Map<String, Object> prepared, Map<String, Object> record, List<FieldErrorItem> errors) {
        logic.defaults().forEach((key, formula) -> {
            FormField field = entity.fieldsByKey().get(key);
            if (field == null || prepared.get(key) != null) return;
            Object value = value(key, formula, record, errors);
            if (value == null) return;
            prepared.put(key, stored(field, value));
            record.put(key, stored(field, value));
        });
    }

    /** Evaluates the computed fields in their order and puts each value into {@code prepared} and {@code record}. */
    public void compute(Map<String, Object> prepared, Map<String, Object> record, List<FieldErrorItem> errors) {
        for (EntityLogic.Computed computed : logic.computed()) {
            FormField field = entity.fieldsByKey().get(computed.field());
            if (field == null) continue;
            Object value = stored(field, value(computed.field(), computed.formula(), record, errors));
            prepared.put(computed.field(), value);
            record.put(computed.field(), value);
        }
    }

    private Set<String> holding(Map<String, Formula> conditions, Map<String, ?> record, List<FieldErrorItem> errors) {
        Set<String> holding = new LinkedHashSet<>();
        conditions.forEach((key, formula) -> {
            try {
                if (formula.holds(EntityFormulaReads.record(record), scope)) holding.add(key);
            } catch (FormulaFailure failure) {
                errors.add(failure.fieldError(key));
            }
        });
        return holding;
    }

    private @Nullable Object value(String key, Formula formula, Map<String, ?> record, List<FieldErrorItem> errors) {
        try {
            return formula.evaluate(EntityFormulaReads.record(record), scope);
        } catch (FormulaFailure failure) {
            // The computed fields are evaluated twice: a failure is reported once.
            FieldErrorItem problem = failure.fieldError(key);
            if (errors.stream()
                    .noneMatch(
                            known -> known.field().equals(key) && known.code().equals(problem.code()))) {
                errors.add(problem);
            }
            return null;
        }
    }

    /**
     * A formula's value as the field keeps it: a number rounded half away from zero to the field's decimals, a date, a
     * moment and a time as their ISO text, a text and a code as they are, a yes/no as a flag.
     */
    public static @Nullable Object stored(FormField field, @Nullable Object value) {
        if (value == null) return null;
        return switch (field.type()) {
            case NUMBER -> {
                Integer scale = field.params().scale();
                BigDecimal number = (BigDecimal) value;
                yield scale == null ? number : number.setScale(scale, RoundingMode.HALF_UP);
            }
            case BOOLEAN -> value;
            default -> FormulaValues.text(value);
        };
    }
}
