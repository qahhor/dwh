package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.Locale;

/**
 * What an entity field holds (ADR-0019, 2.5; ADR-0032, 4.1): the form picks the editor by it, and the list field of
 * the same field has the list type the platform derives from it, so the two cannot disagree (plan 10/10, items 5.0–5.2).
 *
 * <p>A computed value is not a type of its own: it is a field of any scalar type read from
 * {@link FieldSource.Computed} (ADR-0032, 4.1).
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
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
    REF,
    /** An e-mail address, kept in lower case. */
    EMAIL,
    /** A phone number in E.164 ({@code +998901234567}); spaces, brackets and dashes are dropped before the check. */
    PHONE,
    /** An {@code http} or {@code https} address. */
    URL,
    /** An amount and its ISO 4217 currency, {@code {"amount":"1250.00","currency":"UZS"}}; never a double. */
    MONEY,
    /** The code of an item of a reference entity (ADR-0032, 4.5). */
    ENUM,
    /** The keys of several rows of another list, kept in a link table. */
    MULTI_REF,
    /** A stored file, attached to the record (ADR-0032, 4.7). */
    FILE,
    /** A stored PNG, JPEG or WebP image, attached to the record. */
    IMAGE,
    /** A JSON object or array, kept as it is. */
    JSON;

    /** The name the client sees: lower case, as the list registry's types. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the list type alone does not say how to show the value, so the list field names the type as its
     * {@code format}: the kinds added by plan 10/10, item 5.2. The kinds before it show by their list type, so the
     * list metadata of the entities declared earlier does not change.
     */
    public boolean formatted() {
        return switch (this) {
            case TEXT, TEXTAREA, MARKDOWN, NUMBER, DATE, DATETIME, TIME, BOOLEAN, SELECT, REF -> false;
            case EMAIL, PHONE, URL, MONEY, ENUM, MULTI_REF, FILE, IMAGE, JSON -> true;
        };
    }

    /** A single value a list can sort by and an attribute can hold; a set, a file, JSON and money are not. */
    public boolean scalar() {
        return switch (this) {
            case MONEY, MULTI_REF, FILE, IMAGE, JSON -> false;
            default -> true;
        };
    }

    /** Whether a list can sort by the field: one plain value per row; several references, a file and JSON are not. */
    public boolean sortable() {
        return switch (this) {
            case MULTI_REF, FILE, IMAGE, JSON -> false;
            default -> true;
        };
    }
}
