package com.greenwhite.dwh.instance.common.query;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Значения полей реестра: разбор из текста DSL и курсора, запись в курсор, чтение из строки выборки. */
final class QueryValues {

    static final int MAX_TEXT = 500;

    private QueryValues() {
    }

    /** Значение для параметра запроса; {@link IllegalArgumentException} — значение не подходит полю. */
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
            case BOOLEAN -> switch (text) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> throw new IllegalArgumentException("not a boolean");
            };
        };
    }

    static String format(QueryFieldType type, Object value) {
        return switch (type) {
            case NUMBER -> ((BigDecimal) value).toPlainString();
            case INSTANT -> ((OffsetDateTime) value).toInstant().toString();
            default -> value.toString();
        };
    }

    /** Значение сортировки из строки выборки, в том же виде, в каком {@link #parse} отдаёт его параметру. */
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
            case BOOLEAN -> {
                boolean flag = rs.getBoolean(column);
                yield rs.wasNull() ? null : flag;
            }
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
