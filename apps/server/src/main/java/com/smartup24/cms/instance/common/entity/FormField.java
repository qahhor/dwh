package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.field.FieldType;
import com.smartup24.cms.instance.common.query.QueryRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A field of an entity's form (ADR-0019, 2.1): what it holds and the rules a value must meet. The screen draws
 * the form from it and the server checks a record by the same rules ({@link EntityValidator}), so the two
 * cannot disagree.
 *
 * @param key       the property of the record ({@code title})
 * @param labelKey  the dictionary key of its label; empty when {@code label} is given
 * @param label     a ready label instead of a key — a custom field has only its name
 * @param type      what the field holds and which editor draws it
 * @param required  a value must be given
 * @param minLength shortest text, or null
 * @param maxLength longest text, or null
 * @param min       smallest number, or null
 * @param max       largest number, or null
 * @param pattern   a regular expression the whole text must match, or null
 * @param options   the values a select offers, in order
 * @param optionLabelPrefix dictionary prefix of the option labels ({@code notes.color_}); null shows the value
 * @param ref       where a reference field's rows come from (ADR-0019, 2.4)
 * @param attribute a custom field's code: its value lives in the record's {@code attributes}
 */
public record FormField(
        String key,
        String labelKey,
        @Nullable String label,
        FieldType type,
        boolean required,
        @Nullable Integer minLength,
        @Nullable Integer maxLength,
        @Nullable BigDecimal min,
        @Nullable BigDecimal max,
        @Nullable String pattern,
        List<String> options,
        @Nullable String optionLabelPrefix,
        @Nullable QueryRef ref,
        @Nullable String attribute) {

    /** The list registry's key rule, so a form field and its list field can share one name (plan 10/10, item 5.0). */
    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{0,63}$");

    public FormField {
        Objects.requireNonNull(type, "type");
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Bad form field key: " + key);
        }
        options = options == null ? List.of() : List.copyOf(options);
        if ((type == FieldType.SELECT) == options.isEmpty()) {
            throw new IllegalArgumentException(
                    "Form field " + key + ": options go with a select, and a select needs them");
        }
        if ((type == FieldType.REF) != (ref != null)) {
            throw new IllegalArgumentException(
                    "Form field " + key + ": a reference field names its source, and only it");
        }
        if (pattern != null) {
            Pattern.compile(pattern);
        }
    }

    public static FormField of(String key, String labelKey, FieldType type) {
        return new FormField(
                key, labelKey, null, type, false, null, null, null, null, null, List.of(), null, null, null);
    }

    public static FormField select(
            String key, String labelKey, List<String> options, @Nullable String optionLabelPrefix) {
        return new FormField(
                key,
                labelKey,
                null,
                FieldType.SELECT,
                false,
                null,
                null,
                null,
                null,
                null,
                options,
                optionLabelPrefix,
                null,
                null);
    }

    public FormField asRequired() {
        return new FormField(
                key,
                labelKey,
                label,
                type,
                true,
                minLength,
                maxLength,
                min,
                max,
                pattern,
                options,
                optionLabelPrefix,
                ref,
                attribute);
    }

    public FormField length(Integer shortest, Integer longest) {
        return new FormField(
                key,
                labelKey,
                label,
                type,
                required,
                shortest,
                longest,
                min,
                max,
                pattern,
                options,
                optionLabelPrefix,
                ref,
                attribute);
    }

    public FormField range(BigDecimal smallest, BigDecimal largest) {
        return new FormField(
                key,
                labelKey,
                label,
                type,
                required,
                minLength,
                maxLength,
                smallest,
                largest,
                pattern,
                options,
                optionLabelPrefix,
                ref,
                attribute);
    }

    public FormField matching(String regex) {
        return new FormField(
                key,
                labelKey,
                label,
                type,
                required,
                minLength,
                maxLength,
                min,
                max,
                regex,
                options,
                optionLabelPrefix,
                ref,
                attribute);
    }

    /** A custom field of the entity: labelled by its own name, its value in the record's attributes. */
    public FormField custom(String name, String code) {
        return new FormField(
                key,
                "",
                name,
                type,
                required,
                minLength,
                maxLength,
                min,
                max,
                pattern,
                options,
                optionLabelPrefix,
                ref,
                code);
    }

    public FormField refersTo(QueryRef source) {
        return new FormField(
                key,
                labelKey,
                label,
                FieldType.REF,
                required,
                minLength,
                maxLength,
                min,
                max,
                pattern,
                options,
                optionLabelPrefix,
                source,
                attribute);
    }
}
