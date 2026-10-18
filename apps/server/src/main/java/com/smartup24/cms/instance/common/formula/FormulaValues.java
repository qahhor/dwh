package com.smartup24.cms.instance.common.formula;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The values of formulas (ADR-0042): a number is a {@link BigDecimal}, a text or a code a {@link String}, a yes/no a
 * {@link Boolean}, a date a {@link LocalDate}, a moment an {@link Instant} (to the second), a time a {@link LocalTime}
 * and a key a {@link Long}; {@code null} is null. A field's value is read into its formula type: an empty text and a
 * value that does not read as its type are null, an empty yes/no is {@code false} — the form shows it unchecked.
 */
public final class FormulaValues {
    private static final String SPACES = " \t\r\n";
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private FormulaValues() {}

    /** A field's stored or sent value as a value of {@code type}. */
    public static @Nullable Object of(FormulaType type, @Nullable Object raw) {
        if (type == FormulaType.BOOL) return raw instanceof Boolean flag ? flag : "true".equals(String.valueOf(raw));
        if (type.list()) return list(type, raw);
        if (raw == null) return null;
        if (raw instanceof String text && blank(text)) return null;
        return switch (type) {
            case NUMBER -> number(raw);
            case TEXT, OPTION -> String.valueOf(raw);
            case DATE -> date(raw);
            case DATETIME -> moment(raw);
            case TIME -> time(raw);
            case REF -> key(raw);
            case BOOL, NULL, NUMBERS, ROWS -> null;
        };
    }

    /**
     * The values of a column of rows (ADR-0043): numbers as numbers — a value that does not read as one is null — rows
     * and other values as they are, an empty text as null; anything that is not a list is no rows.
     */
    private static List<@Nullable Object> list(FormulaType type, @Nullable Object raw) {
        if (!(raw instanceof List<?> items)) return List.of();
        List<@Nullable Object> values = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item == null || (item instanceof String text && blank(text))) values.add(null);
            else values.add(type == FormulaType.NUMBERS ? number(item) : item);
        }
        return values;
    }

    /** Whether a text holds only spaces, tabs and line breaks — the characters {@code trim} removes. */
    public static boolean blank(String text) {
        return trim(text).isEmpty();
    }

    /** The text without the spaces, tabs and line breaks at its ends. */
    static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && SPACES.indexOf(text.charAt(start)) >= 0) start++;
        while (end > start && SPACES.indexOf(text.charAt(end - 1)) >= 0) end--;
        return text.substring(start, end);
    }

    /** The canonical text of a value: what {@code &} joins and what a text field keeps. */
    public static String text(@Nullable Object value) {
        return switch (value) {
            case null -> "";
            case BigDecimal number -> FormulaNumbers.text(number);
            case Instant moment -> MOMENT.format(moment.atOffset(ZoneOffset.UTC));
            case LocalTime time -> time.toString();
            default -> String.valueOf(value);
        };
    }

    private static @Nullable BigDecimal number(Object raw) {
        try {
            BigDecimal value = raw instanceof BigDecimal decimal
                    ? decimal
                    : new BigDecimal(String.valueOf(raw).strip());
            return FormulaNumbers.normalize(value);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static @Nullable LocalDate date(Object raw) {
        if (raw instanceof LocalDate date) return date;
        if (raw instanceof java.sql.Date date) return date.toLocalDate();
        String text = String.valueOf(raw).strip();
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    private static @Nullable Instant moment(Object raw) {
        Instant moment = switch (raw) {
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            case Date date -> date.toInstant();
            default -> parseMoment(String.valueOf(raw).strip());
        };
        return moment == null ? null : moment.truncatedTo(ChronoUnit.SECONDS);
    }

    private static @Nullable Instant parseMoment(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException notAMoment) {
            return null;
        }
    }

    private static @Nullable LocalTime time(Object raw) {
        if (raw instanceof LocalTime time) return time.truncatedTo(ChronoUnit.SECONDS);
        try {
            return LocalTime.parse(String.valueOf(raw).strip()).truncatedTo(ChronoUnit.SECONDS);
        } catch (DateTimeParseException notATime) {
            return null;
        }
    }

    private static @Nullable Long key(Object raw) {
        if (raw instanceof Number number) return number.longValue();
        try {
            return Long.valueOf(String.valueOf(raw).strip());
        } catch (NumberFormatException notAKey) {
            return null;
        }
    }
}
