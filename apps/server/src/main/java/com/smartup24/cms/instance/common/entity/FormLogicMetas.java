package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.FormMetaController.FormFieldMeta;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaNode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The form logic of an entity as {@code form-meta} gives it (ADR-0042): each field carries the checked trees of its
 * formulas — a computed field is read-only and marked computed, so the form never sends it — and the form its rules,
 * each with the dictionary key of its message. The web evaluates the same trees while the person types; the server
 * evaluates them again on save and stays authoritative.
 */
public final class FormLogicMetas {
    /** A rule as the client sees it: its key, the field its problem is shown at, its message key and its formula. */
    public record FormRuleMeta(String key, @Nullable String field, String messageKey, FormulaNode formula) {}

    private FormLogicMetas() {}

    /** The form with the logic of its entity; the form as it is when the entity has none. */
    static FormMeta with(FormMeta meta, EntityLogic logic) {
        if (logic.empty()) return meta;
        List<FormFieldMeta> fields =
                meta.fields().stream().map(field -> field(field, logic)).toList();
        List<FormRuleMeta> rules = logic.rules().stream()
                .map(rule -> new FormRuleMeta(
                        rule.key(),
                        rule.field(),
                        rule.messageKey(),
                        rule.formula().root()))
                .toList();
        return new FormMeta(
                meta.code(),
                meta.titleKey(),
                meta.listCode(),
                fields,
                meta.layout(),
                meta.actions(),
                meta.capabilities(),
                meta.collections(),
                meta.workflow(),
                meta.tabs(),
                meta.titleField(),
                rules.isEmpty() ? null : rules);
    }

    private static FormFieldMeta field(FormFieldMeta field, EntityLogic logic) {
        String key = field.key();
        Formula computed = logic.computedFormula(key);
        return new FormFieldMeta(
                key,
                field.labelKey(),
                field.label(),
                field.type(),
                field.required(),
                field.readonly() || computed != null,
                field.minLength(),
                field.maxLength(),
                field.min(),
                field.max(),
                field.pattern(),
                field.options(),
                field.optionLabelPrefix(),
                field.ref(),
                field.attribute(),
                field.optionLabels(),
                field.readonlyOnUpdate(),
                field.readonlyWhen(),
                computed != null ? Boolean.TRUE : field.computed(),
                field.defaultValue(),
                field.visibleWhen(),
                field.scale(),
                field.maxItems(),
                field.maxBytes(),
                field.contentTypes(),
                field.currencies(),
                field.jsonRoot(),
                field.currencyFrom(),
                computed == null ? null : computed.root(),
                tree(logic.defaults(), key),
                tree(logic.visible(), key),
                tree(logic.required(), key),
                tree(logic.readonly(), key));
    }

    private static @Nullable FormulaNode tree(Map<String, Formula> formulas, String key) {
        Formula formula = formulas.get(key);
        return formula == null ? null : formula.root();
    }
}
