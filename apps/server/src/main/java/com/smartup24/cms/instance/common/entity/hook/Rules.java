package com.smartup24.cms.instance.common.entity.hook;

import com.smartup24.cms.instance.common.entity.field.FieldCondition;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Ready cross-field rules (ADR-0032, 6.6). A value a field's own rule refuses is that rule's problem: these rules pass
 * over values they cannot read, so one mistake is not reported twice.
 */
public final class Rules {

    private Rules() {}

    /**
     * {@code later} is not before {@code earlier} (both dates): {@code before_start} on {@code later}, the text
     * {@code error.field.not_before} with the label key of the earlier field as {@code field}.
     */
    public static EntityRule notBefore(String later, String earlier) {
        return (values, before, errors) -> {
            LocalDate end = date(values, later);
            LocalDate start = date(values, earlier);
            if (end != null && start != null && end.isBefore(start)) {
                errors.field(later, "before_start", "error.field.not_before", Map.of("field", earlier));
            }
        };
    }

    /** The date of a field, or null without one or when it does not parse (its own rule reports that). */
    private static @Nullable LocalDate date(EntityValues values, String key) {
        try {
            return values.date(key);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code key} has a value while {@code condition} holds over the record: {@code required} on {@code key}. */
    public static EntityRule requiredIf(String key, FieldCondition condition) {
        return (values, before, errors) -> {
            Object value = values.get(key);
            boolean empty = value == null || String.valueOf(value).isBlank();
            if (empty && condition.test(values.asMap())) {
                errors.field(key, "required", "error.field.required");
            }
        };
    }

    /** At least one of {@code keys} has a value: {@code required} on the record, with the keys as {@code fields}. */
    public static EntityRule atLeastOne(String... keys) {
        List<String> fields = Arrays.asList(keys);
        return (values, before, errors) -> {
            boolean any = fields.stream().anyMatch(key -> {
                Object value = values.get(key);
                return value != null && !String.valueOf(value).isBlank();
            });
            if (!any) {
                errors.field(
                        RuleErrors.RECORD,
                        "required",
                        "error.field.at_least_one",
                        Map.of("fields", String.join(", ", fields)));
            }
        };
    }
}
