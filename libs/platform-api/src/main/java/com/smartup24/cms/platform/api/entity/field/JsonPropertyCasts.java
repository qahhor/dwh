package com.smartup24.cms.platform.api.entity.field;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Typed reads of ordinary JSONB fields validated before storage (ADR-0038). A property with a fixed default reads it
 * while the key is absent — a record saved before the field was added (ADR-0041) — and keeps a stored JSON null as
 * empty. The default enters SQL as hexadecimal digits only, so no value of it can change the statement.
 */
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

    static String read(FieldSource.JsonProperty property, String alias, FieldType type, @Nullable String fallback) {
        String raw = fallback == null ? property.sql(alias) : withFallback(property, alias, fallback);
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

    private static String withFallback(FieldSource.JsonProperty property, String alias, String fallback) {
        String hex = HexFormat.of().formatHex(fallback.getBytes(StandardCharsets.UTF_8));
        return "(case when " + alias + "." + property.column() + "->'" + property.key() + "' is null"
                + " then convert_from(decode('" + hex + "', 'hex'), 'UTF8') else " + property.sql(alias) + " end)";
    }
}
