package com.smartup24.cms.instance.common.json;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The JSON columns of a table, written and read with the application's single {@code ObjectMapper} (plan 10/10,
 * item 3.11). A value that cannot be written, or a stored document that cannot be read, is an error: it is logged
 * with the table and raised. It is never replaced by an empty document, which a later save would store over the real
 * one — the silent loss the twenty copied {@code toJson}/{@code parseJson} helpers had.
 */
public final class JsonColumns {

    private static final Logger log = LoggerFactory.getLogger(JsonColumns.class);
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final String table;

    /** @param table the table whose columns these are, named in the log of a failure */
    public JsonColumns(ObjectMapper mapper, String table) {
        this.mapper = mapper;
        this.table = table;
    }

    /** A map as a JSON object; null is the empty object. */
    public String object(@Nullable Map<String, ?> value) {
        return value == null ? "{}" : write(value);
    }

    /** Any value as JSON. */
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            log.error(
                    "json_column_write_failed table={} type={}",
                    table,
                    value.getClass().getName(),
                    e);
            throw new IllegalStateException("A value for a JSON column of " + table + " cannot be written", e);
        }
    }

    /** A stored JSON object; null or blank is the empty map. */
    public Map<String, Object> readObject(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return read(json, OBJECT);
    }

    /** A stored JSON document as the type asked for. */
    public <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            log.error("json_column_read_failed table={} length={}", table, json.length(), e);
            throw new IllegalStateException("A JSON column of " + table + " cannot be read", e);
        }
    }
}
