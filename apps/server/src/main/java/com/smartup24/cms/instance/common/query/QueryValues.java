package com.smartup24.cms.instance.common.query;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Registry field values: parsing from DSL and cursor text, writing to the cursor, reading from a result row. */
final class QueryValues {

    static final int MAX_TEXT = 500;

    private QueryValues() {}

    /** The value for a query parameter; {@link IllegalArgumentException} if the value does not fit the field. */
    static Object parse(QueryField field, String text) {
        if (text == null) {
            throw new IllegalArgumentException("null");
        }
        return switch (field.type()) {
            case TEXT -> {
                if (text.length() > MAX_TEXT) {
                    throw new IllegalArgumentException("too long");
                }
                yield text;
            }
            case ENUM -> {
                if (!field.enumValues().contains(text)) {
                    throw new IllegalArgumentException("unknown value");
                }
                yield text;
            }
            case NUMBER -> new BigDecimal(text);
            case DATE -> parseDate(text);
            case INSTANT -> parseInstant(text);
            case TIME -> parseTime(text);
            case BOOLEAN ->
                switch (text) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> throw new IllegalArgumentException("not a boolean");
                };
            case REF_SET -> parseKey(text);
            case OBJECT -> throw new IllegalArgumentException("takes no value");
        };
    }

    /** A row key of a set of references: a positive whole number. */
    private static Long parseKey(String text) {
        try {
            long key = Long.parseLong(text.strip());
            if (key < 1) {
                throw new IllegalArgumentException("not a key");
            }
            return key;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a key", e);
        }
    }

    static String format(QueryFieldType type, Object value) {
        return switch (type) {
            case NUMBER -> ((BigDecimal) value).toPlainString();
            case INSTANT -> ((OffsetDateTime) value).toInstant().toString();
            default -> value.toString();
        };
    }

    /** The sort value from a result row, in the same form {@link #parse} gives it to a parameter. */
    static Object read(ResultSet rs, String column, QueryFieldType type) throws SQLException {
        Object value = switch (type) {
            case TEXT, ENUM -> rs.getString(column);
            case NUMBER -> rs.getBigDecimal(column);
            case DATE -> {
                Date date = rs.getDate(column);
                yield date == null ? null : date.toLocalDate();
            }
            case INSTANT -> {
                Timestamp timestamp = rs.getTimestamp(column);
                yield timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
            }
            case TIME -> {
                Time time = rs.getTime(column);
                yield time == null ? null : time.toLocalTime();
            }
            case BOOLEAN -> {
                boolean flag = rs.getBoolean(column);
                yield rs.wasNull() ? null : flag;
            }
            case REF_SET, OBJECT -> throw new IllegalStateException("A " + type.wire() + " is never sorted: " + column);
        };
        if (value == null) {
            throw new IllegalStateException("Sort column " + column + " is null; sortable fields must not be");
        }
        return value;
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not a date", e);
        }
    }

    private static LocalTime parseTime(String text) {
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not a time", e);
        }
    }

    private static OffsetDateTime parseInstant(String text) {
        try {
            return Instant.parse(text).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException again) {
                throw new IllegalArgumentException("not an instant", again);
            }
        }
    }
}
