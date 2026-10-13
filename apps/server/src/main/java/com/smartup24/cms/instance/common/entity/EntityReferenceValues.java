package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.math.BigDecimal;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/** Exact numeric ids and unchanged text codes by the reference's declared key (ADR-0032, 4.2). */
public final class EntityReferenceValues {

    private EntityReferenceValues() {}

    /** Whether the value fits the reference's declared key: an exact long id or a text code. */
    public static boolean valid(QueryRef reference, Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return false;
        try {
            value(reference, value);
            return true;
        } catch (ArithmeticException | NumberFormatException e) {
            return false;
        }
    }

    /** A numeric reference key, or none for a code or an invalid value. */
    public static OptionalLong key(@Nullable Object value) {
        if (value == null) return OptionalLong.empty();
        try {
            return OptionalLong.of(numeric(value));
        } catch (ArithmeticException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** The column value: a numeric id exactly, a declared code as text, or null for an empty value. */
    public static @Nullable Object value(QueryRef reference, Object value) {
        if (value instanceof Number) return numeric(value);
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) return null;
        return "id".equals(reference.keyField()) ? (Object) numeric(text) : text;
    }

    private static long numeric(Object value) {
        return value instanceof Number number
                ? new BigDecimal(number.toString()).longValueExact()
                : Long.parseLong(String.valueOf(value).strip());
    }
}
