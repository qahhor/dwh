package com.greenwhite.dwh.instance.common.query;

import com.greenwhite.dwh.core.pagination.CursorUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keyset-курсор списка: значение сортировки и ключ последней строки, итог первой страницы и отпечаток
 * запроса. Курсор от другого фильтра или сортировки отвергается: иначе страница продолжилась бы не тем запросом.
 * Ключ строки хранится текстом: числовой id и UUID ({@code f.id::text}) одинаково годятся.
 */
public record QueryCursor(String fingerprint, Object sortValue, String lastId, long total) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    String encode(QueryField sort) {
        ObjectNode node = JSON.createObjectNode();
        node.put("f", fingerprint);
        node.put("v", QueryValues.format(sort.type(), sortValue));
        node.put("id", lastId);
        node.put("t", total);
        return CursorUtils.encode(node.toString());
    }

    /** Разбирает курсор; {@code null} — курсор битый или от другого запроса. */
    static QueryCursor decode(String cursor, String fingerprint, QueryField sort) {
        String raw = CursorUtils.decode(cursor);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (!fingerprint.equals(node.path("f").asString(null)) || !node.path("id").isString()
                    || !node.path("t").isIntegralNumber() || !node.path("v").isString()) {
                return null;
            }
            Object value = QueryValues.parse(sort, node.path("v").asString());
            return new QueryCursor(fingerprint, value, node.path("id").asString(), node.path("t").asLong());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
