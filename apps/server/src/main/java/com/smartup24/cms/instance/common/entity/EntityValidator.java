package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks a record by its entity's declared fields (ADR-0019, 2.2) — the same rules {@code form-meta} gives the
 * screen. Custom fields are checked by the custom field service, as before; this covers the declared ones. A computed
 * field is not checked (the server writes it), nor is a field its condition hides (ADR-0032, 4.4): the form does not
 * show it, so it is not required and its value is not kept. What needs the database — an enumeration's item, a file,
 * a read-only field's current value — is checked by {@link EntityFieldValues}.
 */
public final class EntityValidator {

    public static final String REQUIRED = "required";
    public static final String TOO_SHORT = "too_short";
    public static final String TOO_LONG = "too_long";
    public static final String OUT_OF_RANGE = "out_of_range";
    public static final String INVALID = "invalid";
    public static final String READONLY = "readonly";

    private static final Pattern TIME = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2})?$");

    private EntityValidator() {}

    /**
     * @param values  the record's values by field key; the conditions of the fields are tested over them, so an update
     *                passes the record as it will be
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
            if (field.attribute() != null || field.computed() || !visible(field, values)) continue;
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
                return FieldValueRules.scale(key, number, field.params().scale());
            }
            case DATE -> {
                try {
                    LocalDate.parse(String.valueOf(value).strip());
                } catch (DateTimeParseException e) {
                    return error(key, INVALID, "error.field.date_required", Map.of());
                }
            }
            case DATETIME -> {
                if (!isMoment(String.valueOf(value).strip())) {
                    return error(key, INVALID, "error.field.datetime_required", Map.of());
                }
            }
            case TIME -> {
                if (!isTime(String.valueOf(value).strip())) {
                    return error(key, INVALID, "error.field.time_required", Map.of());
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
                // A key, a number or a code; the row's existence and visibility are the module's to check with its
                // own scope (until the runtime of plan 10/10, item 5.4 checks them in the target's scope).
                if (!(value instanceof Number) && !(value instanceof String)) {
                    return error(key, INVALID, "error.field.ref_invalid", Map.of());
                }
            }
            case EMAIL, PHONE, URL, MONEY, ENUM, MULTI_REF, FILE, IMAGE, JSON -> {
                return FieldValueRules.problem(field, value);
            }
        }
        return Optional.empty();
    }

    /** Whether the form shows the field over these values: always, or while its condition holds (ADR-0032, 4.4). */
    public static boolean visible(FormField field, Map<String, ?> values) {
        return field.flags().visibleWhen() == null
                || field.flags().visibleWhen().test(values);
    }

    /**
     * The read-only fields a save would change (ADR-0032, 4.4): a value equal to the record's is passed over, so a
     * client may send the whole record; a different one is a {@value #READONLY} problem.
     *
     * @param values   the values of the save by field key
     * @param current  the record's values before it; empty on creation
     * @param creating the save creates the record
     */
    public static List<FieldErrorItem> readonlyProblems(
            EntityDefinition entity, Map<String, ?> values, Map<String, ?> current, boolean creating) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (FormField field : entity.fields()) {
            var readonly = field.flags().readonly();
            if (readonly == null || !values.containsKey(field.key()) || !readonly.applies(creating, current)) {
                continue;
            }
            if (!FieldValueRules.same(field, values.get(field.key()), current.get(field.key()))) {
                errors.add(FieldErrorItem.keyed(field.key(), READONLY, "error.field.readonly"));
            }
        }
        return errors;
    }

    /** An ISO date and time with its offset or {@code Z}: a moment, the same everywhere (plan 10/10, item 5.0). */
    public static boolean isMoment(String text) {
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** A time of day, {@code HH:mm} or {@code HH:mm:ss} (plan 10/10, item 5.0). */
    public static boolean isTime(String text) {
        if (!TIME.matcher(text).matches()) return false;
        try {
            LocalTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Optional<FieldErrorItem> error(String key, String code, String messageKey, Map<String, ?> params) {
        return Optional.of(FieldErrorItem.keyed(key, code, messageKey, params));
    }
}
