package com.smartup24.cms.platform.api.entity.field;

import java.util.Set;

/** Typed reads of ordinary JSONB fields validated before storage (ADR-0038). */
final class JsonPropertyCasts {
    static final Set<FieldType> TYPES = Set.of(
            FieldType.TEXT,
            FieldType.TEXTAREA,
            FieldType.NUMBER,
            FieldType.DATE,
            FieldType.DATETIME,
            FieldType.TIME,
            FieldType.BOOLEAN,
            FieldType.EMAIL,
            FieldType.PHONE,
            FieldType.URL);

    private JsonPropertyCasts() {}

    static String read(FieldSource.JsonProperty property, String alias, FieldType type) {
        String raw = property.sql(alias);
        return switch (type) {
            case TEXT, TEXTAREA, EMAIL, PHONE, URL -> raw;
            case NUMBER -> raw + "::numeric";
            case DATE -> raw + "::date";
            case DATETIME -> raw + "::timestamptz";
            case TIME -> raw + "::time";
            case BOOLEAN -> raw + "::boolean";
            default -> throw new IllegalArgumentException("A JSON property holds no " + type.wire());
        };
    }
}
