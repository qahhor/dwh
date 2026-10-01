package com.smartup24.cms.instance.common.entity.field;

import com.smartup24.cms.instance.common.query.QueryFieldType;
import java.util.Locale;

/**
 * What an entity field holds (ADR-0019, 2.5; ADR-0032, 4.1): the form picks the editor by it, and the list field of
 * the same field has the list type {@link #listType()}, so the two cannot disagree (plan 10/10, items 5.0 and 5.1).
 */
public enum FieldType {
    TEXT,
    TEXTAREA,
    MARKDOWN,
    NUMBER,
    DATE,
    /** A moment: an ISO date and time with its offset ({@code 2026-10-01T09:30:00Z}); its list field is an instant. */
    DATETIME,
    /** A time of day, {@code HH:mm} or {@code HH:mm:ss}. */
    TIME,
    BOOLEAN,
    SELECT,
    REF;

    /** The name the client sees: lower case, as the list registry's types. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The list type the field is shown, filtered and exported as: text kinds as text, a moment as an instant, a select
     * as an enumeration of its options, a reference as the number key of the row it names.
     */
    public QueryFieldType listType() {
        return switch (this) {
            case TEXT, TEXTAREA, MARKDOWN -> QueryFieldType.TEXT;
            case NUMBER, REF -> QueryFieldType.NUMBER;
            case DATE -> QueryFieldType.DATE;
            case DATETIME -> QueryFieldType.INSTANT;
            case TIME -> QueryFieldType.TIME;
            case BOOLEAN -> QueryFieldType.BOOLEAN;
            case SELECT -> QueryFieldType.ENUM;
        };
    }
}
