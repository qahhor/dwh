package com.greenwhite.dwh.instance.common.entity;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.common.error.ApiException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private EntityValidator() {
    }

    /**
     * @param values  the record's values by field key
     * @param partial an update: a field that is absent keeps its value and is not required again
     * @throws ApiException 422 with every problem addressed to its field
     */
    public static void check(EntityDefinition entity, Map<String, ?> values, boolean partial) {
        List<FieldErrorItem> errors = problems(entity, values, partial);
        if (!errors.isEmpty()) {
            throw ApiException.validation("Проверьте поля записи", errors);
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
                    errors.add(new FieldErrorItem(field.key(), REQUIRED, "Поле обязательно"));
                }
                continue;
            }
            problem(field, value).ifPresent(errors::add);
        }
        return errors;
    }

    private static java.util.Optional<FieldErrorItem> problem(FormField field, Object value) {
        String key = field.key();
        switch (field.type()) {
            case TEXT, TEXTAREA, MARKDOWN -> {
                String text = String.valueOf(value);
                int length = text.strip().length();
                if (field.minLength() != null && length < field.minLength()) {
                    return error(key, TOO_SHORT, "Не короче " + field.minLength() + " символов");
                }
                if (field.maxLength() != null && text.length() > field.maxLength()) {
                    return error(key, TOO_LONG, "Не длиннее " + field.maxLength() + " символов");
                }
                if (field.pattern() != null && !Pattern.matches(field.pattern(), text)) {
                    return error(key, INVALID, "Значение не подходит под формат");
                }
            }
            case NUMBER -> {
                BigDecimal number;
                try {
                    number = new BigDecimal(String.valueOf(value).strip());
                } catch (NumberFormatException e) {
                    return error(key, INVALID, "Нужно число");
                }
                if ((field.min() != null && number.compareTo(field.min()) < 0)
                        || (field.max() != null && number.compareTo(field.max()) > 0)) {
                    return error(key, OUT_OF_RANGE, "Число вне допустимого диапазона");
                }
            }
            case DATE -> {
                try {
                    LocalDate.parse(String.valueOf(value).strip());
                } catch (DateTimeParseException e) {
                    return error(key, INVALID, "Нужна дата ГГГГ-ММ-ДД");
                }
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean) && !List.of("true", "false").contains(String.valueOf(value))) {
                    return error(key, INVALID, "Нужно да или нет");
                }
            }
            case SELECT -> {
                if (!field.options().contains(String.valueOf(value))) {
                    return error(key, INVALID, "Выберите один из вариантов");
                }
            }
            case REF -> {
                // The referenced row's existence and visibility are the module's to check with its own scope.
            }
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<FieldErrorItem> error(String key, String code, String message) {
        return java.util.Optional.of(new FieldErrorItem(key, code, message));
    }
}
