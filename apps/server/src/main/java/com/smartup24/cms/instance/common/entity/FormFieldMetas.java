package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.EntityEnums.Items;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormFieldMeta;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.FieldCondition;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldParams;
import com.smartup24.cms.platform.api.entity.field.FieldReadonly;
import com.smartup24.cms.platform.api.entity.field.FieldReadonly.Mode;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.FormFlags;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A form field as {@code form-meta} gives it (ADR-0032, 3.3 and 4): the flags and parameters a field does not have
 * are left out of the answer, so the forms of the entities declared before plan 10/10, item 5.2 answer as they did.
 * Only {@code readonly} is always given: true when the field is read-only whatever the record's state — the viewer
 * lacks its right (ADR-0032, 5.2) or the declaration says {@code ALWAYS}; the conditional kinds of ADR-0032, 4.4 come
 * as {@code readonlyOnUpdate} and {@code readonlyWhen}.
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

    /**
     * A default: its kind ({@code fixed}, {@code now}, {@code today}, {@code current_user}, {@code current_org_unit},
     * {@code sequence}).
     */
    public record DefaultValueMeta(String kind, @Nullable String value) {}

    private FormFieldMetas() {}

    /**
     * The field as the viewer sees it.
     *
     * @param enumItems      the items of a reference entity by its code
     * @param noRightToWrite the viewer lacks the field's {@code readonlyUnless} right: read-only whatever the state
     */
    static FormFieldMeta of(FormField field, Function<String, Items> enumItems, boolean noRightToWrite) {
        FormFlags flags = field.flags();
        FieldParams params = field.params();
        Items items = field.type() == FieldType.ENUM && params.enumeration() != null
                ? enumItems.apply(params.enumeration())
                : null;
        FieldReadonly declared = flags.readonly();
        Mode mode = declared == null ? null : declared.mode();
        boolean readonly = noRightToWrite || mode == Mode.ALWAYS;
        FieldDefault defaultValue = flags.defaultValue();
        return new FormFieldMeta(
                field.key(),
                field.labelKey(),
                field.label(),
                field.type().wire(),
                field.required(),
                readonly,
                field.minLength(),
                field.maxLength(),
                field.min(),
                field.max(),
                field.pattern(),
                items == null ? field.options() : List.copyOf(items.offered().keySet()),
                field.optionLabelPrefix(),
                field.ref(),
                field.attribute(),
                items == null ? null : items.names(),
                !readonly && mode == Mode.ON_UPDATE ? Boolean.TRUE : null,
                !readonly && mode == Mode.WHEN
                        ? condition(Objects.requireNonNull(declared).when())
                        : null,
                flags.computed() ? Boolean.TRUE : null,
                defaultValue == null ? null : new DefaultValueMeta(defaultValue.kind(), defaultValue.value()),
                condition(flags.visibleWhen()),
                params.scale(),
                params.maxItems(),
                params.maxBytes(),
                params.contentTypes().isEmpty() ? null : params.contentTypes(),
                params.currencies().isEmpty() ? null : params.currencies(),
                params.jsonRoot() == null ? null : params.jsonRoot().wire(),
                params.currencyFrom());
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
