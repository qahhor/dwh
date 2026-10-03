package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.EnumSet;
import java.util.Set;

/**
 * How a scalar value kept in the record's {@code attributes} reads in the list (ADR-0032, 4.1, storage rule 2): as
 * text for the text kinds and a select, cast to its type for the others — but only a value of the right shape, so a
 * value written before the field had its type reads as empty instead of failing the whole page (as the custom fields
 * do, ADR-0019, 2.3). Never sorted: that needs an expression index.
 */
@PlatformApi(since = "1.0", stability = Stability.EXPERIMENTAL)
public final class AttributeCasts {

    /** The kinds an attribute holds. */
    public static final Set<FieldType> TYPES = EnumSet.of(
            FieldType.TEXT,
            FieldType.TEXTAREA,
            FieldType.MARKDOWN,
            FieldType.SELECT,
            FieldType.EMAIL,
            FieldType.PHONE,
            FieldType.URL,
            FieldType.NUMBER,
            FieldType.DATE,
            FieldType.DATETIME,
            FieldType.TIME,
            FieldType.BOOLEAN);

    private static final String NUMBER = "^-?[0-9]{1,15}([.][0-9]{1,6})?$";
    private static final String DATE = "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$";
    private static final String MOMENT = "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3])"
            + ":[0-5][0-9](:[0-5][0-9]([.][0-9]{1,9})?)?(Z|[+-]([01][0-9]|2[0-3])(:?[0-5][0-9])?)$";
    private static final String TIME_OF_DAY = "^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$";

    private AttributeCasts() {}

    /** The attribute {@code code} of a field of {@code type} as SQL over the table aliased {@code alias}. */
    public static String read(String alias, String code, FieldType type) {
        String raw = "(" + alias + ".attributes->>'" + code + "')";
        return switch (type) {
            case TEXT, TEXTAREA, MARKDOWN, SELECT, EMAIL, PHONE, URL -> raw;
            case NUMBER -> cast(raw, NUMBER, raw + "::numeric");
            case DATE -> cast(raw, DATE, raw + "::date");
            case DATETIME -> cast(raw, MOMENT, raw + "::timestamptz");
            case TIME -> cast(raw, TIME_OF_DAY, raw + "::time");
            case BOOLEAN -> "(case when lower(" + raw + ") in ('true', 'false') then lower(" + raw + ")::boolean end)";
            default -> throw new IllegalArgumentException("An attribute holds no " + type.wire());
        };
    }

    private static String cast(String raw, String shape, String typed) {
        return "(case when " + raw + " ~ '" + shape + "' then " + typed + " end)";
    }
}
