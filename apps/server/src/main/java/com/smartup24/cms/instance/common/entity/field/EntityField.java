package com.smartup24.cms.instance.common.entity.field;

import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One field of an entity, declared once (ADR-0032, 3.1; plan 10/10, item 5.1): the form field ({@link #formField()})
 * and the list fields ({@link #queryFields(String, Function)}) are derived from it, so the form and the list cannot disagree on
 * the key, the label, the kind of value, the options or the reference. Built with {@link EntityFields}; what each
 * type needs is checked by {@link FieldTypeRules} (plan 10/10, item 5.2).
 *
 * @param key        the record property in the API and the DSL
 * @param labelKey   the dictionary key of its label; empty when {@code label} is given
 * @param label      a ready label instead of a key, or null
 * @param type       what it holds
 * @param source     where its value lives
 * @param form       what it is on the form, or null when it is only in the list
 * @param list       what it is in the list, or null when it is only on the form
 * @param access     the rights on the field
 * @param options    the parameters of its type
 * @param exported   a column of the list export; by default when it is in the list
 * @param history    written to the audit and the history; by default when it is written
 * @param importable a column of the import template; by default when the form writes it
 */
public record EntityField(
        String key,
        String labelKey,
        @Nullable String label,
        FieldType type,
        FieldSource source,
        @Nullable FormPart form,
        @Nullable ListPart list,
        FieldAccess access,
        FieldOptions options,
        boolean exported,
        boolean history,
        boolean importable) {

    /** One rule for the form, the list and the DSL (plan 10/10, item 5.0). */
    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{0,63}$");

    /** The key suffix of the hidden list field that filters money by its currency (ADR-0032, 4.1). */
    public static final String CURRENCY_SUFFIX = "Currency";

    /** The format of that currency field: its value is the currency of the money field it belongs to. */
    public static final String CURRENCY_FORMAT = "currency";

    public EntityField {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(options, "options");
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Bad entity field key: " + key);
        }
        if (form == null && list == null) {
            throw new IllegalArgumentException("Entity field " + key + " is on neither the form nor the list");
        }
        if (form != null && !source.writable() && !(source instanceof FieldSource.Computed)) {
            throw new IllegalArgumentException("Entity field " + key
                    + ": only a column, an attribute, money columns, a link table or a computed value is on the form");
        }
        if (source instanceof FieldSource.SystemValue system
                && !system.column().key().equals(key)) {
            throw new IllegalArgumentException("Entity field " + key + ": a system column answers as "
                    + system.column().key());
        }
        FieldTypeRules.check(key, type, source, form, list, options);
    }

    /** The field of the form, or null when the field is only in the list. */
    public @Nullable FormField formField() {
        if (form == null) return null;
        FieldRules rules = form.rules();
        boolean computed = source instanceof FieldSource.Computed;
        FormFlags flags = new FormFlags(
                computed ? FieldReadonly.ALWAYS : form.readonly(), form.defaultValue(), form.visibleWhen(), computed);
        FieldRules typed = type == FieldType.IMAGE && rules.contentTypes().isEmpty()
                ? rules.withFiles(rules.maxBytes(), FieldTypeRules.IMAGE_TYPES)
                : rules;
        return new FormField(
                key,
                labelKey,
                label,
                type,
                form.required(),
                rules.minLength(),
                rules.maxLength(),
                rules.min(),
                rules.max(),
                rules.pattern(),
                options.options(),
                options.optionLabelPrefix(),
                options.ref(),
                attribute(),
                flags,
                FieldParams.of(typed, options));
    }

    /**
     * The field of the entity's list, its value read over the entity's table aliased {@code alias}, or null when the
     * field is only on the form.
     */
    public @Nullable QueryField queryField(String alias) {
        if (list == null) return null;
        QueryFieldType listType = type.listType();
        boolean enumeration = listType == QueryFieldType.ENUM;
        return new QueryField(
                key,
                labelKey,
                listType,
                sql(alias),
                list.isFilterable(),
                list.isSortable(),
                list.isNullable() || !listType.sortable() || source instanceof FieldSource.Attribute,
                list.isDefaultVisible(),
                enumeration ? options.options() : List.of(),
                enumeration ? options.optionLabelPrefix() : null,
                list.isSearchable(),
                access.requiredForm(),
                access.requiredAction(),
                label,
                attribute(),
                options.ref(),
                type.formatted() ? type.wire() : null,
                null);
    }

    /**
     * The fields of the entity's list: the field itself and, for money, the hidden field that filters it by its
     * currency ({@code totalCurrency}, an enumeration of its currencies; ADR-0032, 4.1). Empty when the field is only
     * on the form.
     *
     * @param fieldSql the SQL of another field of the record by its key: the currency of money that takes it from a
     *                 field ({@link FieldOptions#currencyFrom()})
     */
    public List<QueryField> queryFields(String alias, Function<String, String> fieldSql) {
        QueryField field = queryField(alias);
        if (field == null) return List.of();
        List<QueryField> fields = new ArrayList<>();
        fields.add(field);
        if (type == FieldType.MONEY) {
            fields.add(new QueryField(
                    key + CURRENCY_SUFFIX,
                    labelKey,
                    QueryFieldType.ENUM,
                    currencySql(alias, fieldSql),
                    field.filterable(),
                    false,
                    false,
                    false,
                    options.currencies(),
                    null,
                    false,
                    field.requiredForm(),
                    field.requiredAction(),
                    label,
                    null,
                    null,
                    CURRENCY_FORMAT,
                    null));
        }
        return fields;
    }

    /** The fields of the entity's list, for a field whose money keeps its currency itself. */
    public List<QueryField> queryFields(String alias) {
        return queryFields(alias, key -> {
            throw new IllegalStateException("Entity field " + this.key + " takes its currency from " + key);
        });
    }

    /**
     * The currency of money as SQL over the table aliased {@code alias} (ADR-0032, 4.1 and 9.1): its currency column,
     * the select field it takes the currency from ({@code fieldSql} gives that field's SQL), or its one currency as a
     * literal.
     */
    public String currencySql(String alias, Function<String, String> fieldSql) {
        if (type != FieldType.MONEY) {
            throw new IllegalStateException("Entity field " + key + " is no money");
        }
        if (source instanceof FieldSource.MoneyColumns money && money.currency() != null) {
            return money.currencySql(alias, options.currencies().getFirst());
        }
        if (options.currencyFrom() != null) {
            return fieldSql.apply(options.currencyFrom());
        }
        String fixed = options.currencies().getFirst();
        if (!FieldSource.CURRENCY.matcher(fixed).matches()) {
            throw new IllegalArgumentException("Bad currency: " + fixed);
        }
        return "'" + fixed + "'";
    }

    /** The value as SQL over the table aliased {@code alias}: an attribute cast to the field's type. */
    public String sql(String alias) {
        return source instanceof FieldSource.Attribute attribute
                ? AttributeCasts.read(alias, attribute.code(), type)
                : source.sql(alias);
    }

    /** The attribute code of a field kept in the record's {@code attributes}, or null. */
    public @Nullable String attribute() {
        return source instanceof FieldSource.Attribute attribute ? attribute.code() : null;
    }
}
