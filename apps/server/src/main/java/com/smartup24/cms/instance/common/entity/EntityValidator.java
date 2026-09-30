package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks a record by its entity's declared fields (ADR-0019, 2.2) — the same rules {@code form-meta} gives the
 * screen. Custom fields are checked by the custom field service, as before; this covers the declared ones.
 */
public final class EntityValidator {

    public static final String REQUIRED = "required";
    public static final String TOO_SHORT = "too_short";
    public static final String TOO_LONG = "too_long";
    public static final String OUT_OF_RANGE = "out_of_range";
    public static final String INVALID = "invalid";

    private EntityValidator() {}

    /**
     * @param values  the record's values by field key
     * @param partial an update: a field that is absent keeps its value and is not required again
     * @throws ApiException 422 with every problem addressed to its field
     */
    public static void check(EntityDefinition entity, Map<String, ?> values, boolean partial) {
        List<FieldErrorItem> errors = problems(entity, values, partial);
        if (!errors.isEmpty()) {
            throw ApiException.validation("error.common.record_fields_invalid", errors);
        }
    }

    public static List<FieldErrorItem> problems(EntityDefinition entity, Map<String, ?> values, boolean partial) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (FormField field : entity.fields()) {
            if (field.attribute() != null) continue;
            boolean present = values.containsKey(field.key());
            Object value = values.get(field.key());
            if (value == null || (value instanceof String text && text.isBlank())) {
                if (field.required() && (!partial || present)) {
                    errors.add(FieldErrorItem.keyed(field.key(), REQUIRED, "error.field.required"));
                }
                continue;
            }
            problem(field, value).ifPresent(errors::add);
        }
        return errors;
    }

    private static Optional<FieldErrorItem> problem(FormField field, Object value) {
        String key = field.key();
        switch (field.type()) {
            case TEXT, TEXTAREA, MARKDOWN -> {
                String text = String.valueOf(value);
                int length = text.strip().length();
                if (field.minLength() != null && length < field.minLength()) {
                    return error(key, TOO_SHORT, "error.field.too_short", Map.of("min", field.minLength()));
                }
                if (field.maxLength() != null && text.length() > field.maxLength()) {
                    return error(key, TOO_LONG, "error.field.too_long", Map.of("max", field.maxLength()));
                }
                if (field.pattern() != null && !Pattern.matches(field.pattern(), text)) {
                    return error(key, INVALID, "error.field.pattern_mismatch", Map.of());
                }
            }
            case NUMBER -> {
                BigDecimal number;
                try {
                    number = new BigDecimal(String.valueOf(value).strip());
                } catch (NumberFormatException e) {
                    return error(key, INVALID, "error.field.number_required", Map.of());
                }
                if ((field.min() != null && number.compareTo(field.min()) < 0)
                        || (field.max() != null && number.compareTo(field.max()) > 0)) {
                    return error(key, OUT_OF_RANGE, "error.field.number_out_of_range", Map.of());
                }
            }
            case DATE -> {
                try {
                    LocalDate.parse(String.valueOf(value).strip());
                } catch (DateTimeParseException e) {
                    return error(key, INVALID, "error.field.date_required", Map.of());
                }
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean) && !List.of("true", "false").contains(String.valueOf(value))) {
                    return error(key, INVALID, "error.field.yes_no_required", Map.of());
                }
            }
            case SELECT -> {
                if (!field.options().contains(String.valueOf(value))) {
                    return error(key, INVALID, "error.field.option_required", Map.of());
                }
            }
            case REF -> {
                // The referenced row's existence and visibility are the module's to check with its own scope.
            }
        }
        return Optional.empty();
    }

    private static Optional<FieldErrorItem> error(String key, String code, String messageKey, Map<String, ?> params) {
        return Optional.of(FieldErrorItem.keyed(key, code, messageKey, params));
    }
}
