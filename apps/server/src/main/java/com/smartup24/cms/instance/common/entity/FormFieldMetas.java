package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.FormMetaController.FormFieldMeta;
import com.smartup24.cms.instance.common.entity.field.FieldCondition;
import com.smartup24.cms.instance.common.entity.field.FieldDefault;
import com.smartup24.cms.instance.common.entity.field.FieldParams;
import com.smartup24.cms.instance.common.entity.field.FieldReadonly;
import com.smartup24.cms.instance.common.entity.field.FieldType;
import com.smartup24.cms.instance.common.entity.field.FormFlags;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A form field as {@code form-meta} gives it (ADR-0032, 3.3 and 4): the flags and parameters a field does not have
 * are left out of the answer, so the forms of the entities declared before plan 10/10, item 5.2 answer as they did.
 */
public final class FormFieldMetas {

    /**
     * One test of a condition as the client sees it, the filter DSL's shape (ADR-0016): {@code field}, {@code op}
     * ({@code eq}, {@code ne}, {@code in}, {@code empty}, {@code not_empty}) and the {@code values} compared as text.
     */
    public record ConditionClauseMeta(String field, String op, List<String> values) {}

    /**
     * One item of a condition: a clause, or {@code {"any": [...]}} — clauses of which one must hold. Every item must
     * hold.
     */
    public record ConditionItemMeta(
            @Nullable String field,
            @Nullable String op,
            @Nullable List<String> values,
            @Nullable List<ConditionClauseMeta> any) {}

    /** A default: its kind ({@code fixed}, {@code now}, {@code today}, {@code current_user}, {@code sequence}). */
    public record DefaultValueMeta(String kind, @Nullable String value) {}

    private FormFieldMetas() {}

    static FormFieldMeta of(FormField field, Function<String, Map<String, String>> enumItems) {
        FormFlags flags = field.flags();
        FieldParams params = field.params();
        Map<String, String> items = field.type() == FieldType.ENUM && params.enumeration() != null
                ? enumItems.apply(params.enumeration())
                : null;
        FieldReadonly readonly = flags.readonly();
        FieldDefault defaultValue = flags.defaultValue();
        return new FormFieldMeta(
                field.key(),
                field.labelKey(),
                field.label(),
                field.type().wire(),
                field.required(),
                field.minLength(),
                field.maxLength(),
                field.min(),
                field.max(),
                field.pattern(),
                items == null ? field.options() : List.copyOf(items.keySet()),
                field.optionLabelPrefix(),
                field.ref(),
                field.attribute(),
                items,
                readonly == null ? null : readonly.mode().wire(),
                readonly == null ? null : condition(readonly.when()),
                flags.computed() ? Boolean.TRUE : null,
                defaultValue == null ? null : new DefaultValueMeta(defaultValue.kind(), defaultValue.value()),
                condition(flags.visibleWhen()),
                params.scale(),
                params.maxItems(),
                params.maxBytes(),
                params.contentTypes().isEmpty() ? null : params.contentTypes(),
                params.currencies().isEmpty() ? null : params.currencies(),
                params.jsonRoot() == null ? null : params.jsonRoot().wire());
    }

    /** A condition as items: a group of one clause as the clause, a group of several as {@code any}. */
    static @Nullable List<ConditionItemMeta> condition(@Nullable FieldCondition condition) {
        if (condition == null) return null;
        return condition.groups().stream()
                .map(group -> group.size() == 1
                        ? new ConditionItemMeta(
                                group.getFirst().field(),
                                group.getFirst().op().wire(),
                                group.getFirst().values(),
                                null)
                        : new ConditionItemMeta(
                                null,
                                null,
                                null,
                                group.stream().map(FormFieldMetas::clause).toList()))
                .toList();
    }

    private static ConditionClauseMeta clause(FieldCondition.Clause clause) {
        return new ConditionClauseMeta(clause.field(), clause.op().wire(), clause.values());
    }
}
