package com.smartup24.cms.instance.common.entity.field;

import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What each type of field needs and refuses (ADR-0032, 4.1), checked when a field is declared, so a declaration that
 * the list, the form or the store could not serve fails the start rather than a request.
 */
final class FieldTypeRules {

    /** The content types an image takes (ADR-0032, 4.2); no thumbnails are made, the browser scales the original. */
    static final List<String> IMAGE_TYPES = List.of("image/png", "image/jpeg", "image/webp");

    /** A key that names a secret: secrets never live in entity fields, only in sealed columns (ADR-0029). */
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i).*(password|passwd|secret|apikey|privatekey|accesstoken|refreshtoken).*");

    private FieldTypeRules() {}

    static void check(
            String key,
            FieldType type,
            FieldSource source,
            @Nullable FormPart form,
            @Nullable ListPart list,
            FieldOptions options) {
        if (SECRET_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Entity field " + key + ": secrets live in sealed columns (ADR-0029)");
        }
        require(
                key,
                (type == FieldType.SELECT) != options.options().isEmpty(),
                "options go with a select, and a select needs them");
        require(
                key,
                (type == FieldType.REF || type == FieldType.MULTI_REF) == (options.ref() != null),
                "a reference field names its source, and only it");
        require(
                key,
                (type == FieldType.ENUM) == (options.enumeration() != null),
                "an enumeration names its reference entity, and only it");
        require(
                key,
                (type == FieldType.MONEY) != options.currencies().isEmpty(),
                "money names its currencies, and only money has them");
        require(key, (type == FieldType.JSON) || options.jsonRoot() == null, "only JSON has a root");
        require(
                key,
                source instanceof FieldSource.MoneyColumns
                        ? type == FieldType.MONEY
                        : type != FieldType.MONEY || source instanceof FieldSource.Computed,
                "money lives in a pair of money columns or is computed, and only money lives in money columns");
        require(
                key,
                source instanceof FieldSource.Link
                        ? type == FieldType.MULTI_REF
                        : type != FieldType.MULTI_REF || source instanceof FieldSource.Expression,
                "several references live in a link table, and only they do; an expression may read them");
        if (source instanceof FieldSource.MoneyColumns money) {
            require(
                    key,
                    money.currency() != null || options.currencies().size() == 1 || options.currencyFrom() != null,
                    "money without a currency column holds one currency or takes it from a field");
            require(
                    key,
                    money.currency() == null || options.currencyFrom() == null,
                    "money keeps its currency in its column or takes it from a field, not both");
        }
        if (type == FieldType.MONEY && source instanceof FieldSource.Computed) {
            require(
                    key,
                    options.currencies().size() == 1 || options.currencyFrom() != null,
                    "computed money holds one currency or takes it from a field");
        }
        if (type == FieldType.FILE || type == FieldType.IMAGE || type == FieldType.JSON) {
            require(key, source instanceof FieldSource.Column, "a file and JSON live in a column of their own");
        }
        if (type == FieldType.IMAGE && form != null) {
            require(key, IMAGE_TYPES.containsAll(form.rules().contentTypes()), "an image is PNG, JPEG or WebP");
        }
        if (source instanceof FieldSource.Attribute) {
            require(
                    key,
                    AttributeCasts.TYPES.contains(type) && (list == null || !list.isSortable()),
                    "an attribute holds a scalar value and is never sorted (ADR-0019, 2.3)");
        }
        if (source instanceof FieldSource.Computed) {
            require(key, type.scalar() || type == FieldType.MONEY, "a computed value is a scalar or money");
        }
        if (list != null && list.isSortable()) {
            require(key, type.listType().sortable(), "a " + type.wire() + " is never sorted");
        }
        if (form != null) {
            checkForm(key, source, form);
        }
    }

    private static void checkForm(String key, FieldSource source, FormPart form) {
        if (source instanceof FieldSource.Computed) {
            require(
                    key,
                    !form.required() && form.defaultValue() == null && form.visibleWhen() == null,
                    "a computed value is shown as it is: never required, defaulted or hidden");
        }
        if (form.defaultValue() instanceof FieldDefault.Sequence) {
            require(
                    key,
                    form.readonly() != null && form.readonly().mode() == FieldReadonly.Mode.ALWAYS,
                    "a number from a sequence is read-only");
        }
        if (form.visibleWhen() != null) {
            require(key, !form.visibleWhen().fields().contains(key), "a field is not shown by its own value");
        }
    }

    private static void require(String key, boolean holds, String rule) {
        if (!holds) {
            throw new IllegalArgumentException("Entity field " + key + ": " + rule);
        }
    }
}
