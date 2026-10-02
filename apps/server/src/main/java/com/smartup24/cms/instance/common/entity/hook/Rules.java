package com.smartup24.cms.instance.common.entity.hook;

import com.smartup24.cms.instance.common.entity.field.FieldCondition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /** The length of an ISO date, {@code 2026-10-01}. */
    private static final int DATE_LENGTH = 10;

    private Rules() {}

    /**
     * {@code later} is not before {@code earlier} (both dates, or both moments): {@code before_start} on
     * {@code later}, the text {@code error.field.not_before} with the label key of the earlier field as
     * {@code field}.
     */
    public static EntityRule notBefore(String later, String earlier) {
        return (values, before, errors) -> {
            Instant end = moment(values, later);
            Instant start = moment(values, earlier);
            if (end != null && start != null && end.isBefore(start)) {
                errors.field(later, "before_start", "error.field.not_before", Map.of("field", earlier));
            }
        };
    }

    /**
     * The date of a field as the moment it starts in UTC, or the moment of a date-time field; null without a value or
     * when it does not parse (its own rule reports that).
     */
    private static @Nullable Instant moment(EntityValues values, String key) {
        String text = values.text(key);
        if (text == null || text.isBlank()) return null;
        try {
            return text.strip().length() == DATE_LENGTH
                    ? LocalDate.parse(text.strip()).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : OffsetDateTime.parse(text.strip()).toInstant();
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
