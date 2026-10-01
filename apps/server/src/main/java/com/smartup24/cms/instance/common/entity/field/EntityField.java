package com.smartup24.cms.instance.common.entity.field;

import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One field of an entity, declared once (ADR-0032, 3.1; plan 10/10, item 5.1): the form field ({@link #formField()})
 * and the list field ({@link #queryField(String)}) are derived from it, so the form and the list cannot disagree on
 * the key, the label, the kind of value, the options or the reference. Built with {@link EntityFields}.
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

    /** Kinds of value an attribute holds as text, so its list field reads it without a cast (until item 5.2). */
    private static final Set<FieldType> TEXT_ATTRIBUTES =
            EnumSet.of(FieldType.TEXT, FieldType.TEXTAREA, FieldType.MARKDOWN, FieldType.SELECT);

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
        if (form != null && !source.writable()) {
            throw new IllegalArgumentException(
                    "Entity field " + key + ": only a column or an attribute is written by the form");
        }
        if ((type == FieldType.SELECT) == options.options().isEmpty()) {
            throw new IllegalArgumentException(
                    "Entity field " + key + ": options go with a select, and a select needs them");
        }
        if ((type == FieldType.REF) != (options.ref() != null)) {
            throw new IllegalArgumentException(
                    "Entity field " + key + ": a reference field names its source, and only it");
        }
        if (source instanceof FieldSource.SystemValue system
                && !system.column().key().equals(key)) {
            throw new IllegalArgumentException("Entity field " + key + ": a system column answers as "
                    + system.column().key());
        }
        if (source instanceof FieldSource.Attribute
                && (!TEXT_ATTRIBUTES.contains(type) || (list != null && list.isSortable()))) {
            throw new IllegalArgumentException("Entity field " + key
                    + ": an attribute holds text or a select option and is never sorted (ADR-0019, 2.3)");
        }
    }

    /** The field of the form, or null when the field is only in the list. */
    public @Nullable FormField formField() {
        if (form == null) return null;
        FieldRules rules = form.rules();
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
                attribute());
    }

    /**
     * The field of the entity's list, its value read over the entity's table aliased {@code alias}, or null when the
     * field is only on the form.
     */
    public @Nullable QueryField queryField(String alias) {
        if (list == null) return null;
        QueryFieldType listType = type.listType();
        return new QueryField(
                key,
                labelKey,
                listType,
                source.sql(alias),
                list.isFilterable(),
                list.isSortable(),
                list.isNullable(),
                list.isDefaultVisible(),
                listType == QueryFieldType.ENUM ? options.options() : List.of(),
                listType == QueryFieldType.ENUM ? options.optionLabelPrefix() : null,
                list.isSearchable(),
                access.requiredForm(),
                access.requiredAction(),
                label,
                attribute(),
                options.ref());
    }

    /** The attribute code of a field kept in the record's {@code attributes}, or null. */
    public @Nullable String attribute() {
        return source instanceof FieldSource.Attribute attribute ? attribute.code() : null;
    }
}
