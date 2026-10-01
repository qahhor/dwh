package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.pagination.CursorUtils;
import java.time.DateTimeException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A list keyset cursor: the sort value and key of the last row, the first page's total and the query
 * fingerprint. A cursor from another filter or sort is rejected; otherwise the page would continue a different
 * query. The row key is stored as text, so a numeric id and a UUID ({@code f.id::text}) both work.
 */
public record QueryCursor(String fingerprint, Object sortValue, String lastId, long total) {

    private static final Logger log = LoggerFactory.getLogger(QueryCursor.class);

    /**
     * A value type with no Spring context writes and reads its own small tree with default settings: the shared
     * default mapper does that (plan 10/10, item 3.11).
     */
    private static final JsonMapper JSON = JsonMapper.shared();

    String encode(QueryField sort) {
        ObjectNode node = JSON.createObjectNode();
        node.put("f", fingerprint);
        node.put("v", QueryValues.format(sort.type(), sortValue));
        node.put("id", lastId);
        node.put("t", total);
        return CursorUtils.encode(node.toString());
    }

    /** Parses a cursor; {@code null} means the cursor is broken or belongs to another query. */
    static @Nullable QueryCursor decode(String cursor, String fingerprint, QueryField sort) {
        String raw = CursorUtils.decode(cursor);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (!fingerprint.equals(node.path("f").asString(null))
                    || !node.path("id").isString()
                    || !node.path("t").isIntegralNumber()
                    || !node.path("v").isString()) {
                return null;
            }
            Object value = QueryValues.parse(sort, node.path("v").asString());
            return new QueryCursor(
                    fingerprint,
                    value,
                    node.path("id").asString(),
                    node.path("t").asLong());
        } catch (JacksonException | IllegalArgumentException | DateTimeException e) {
            // A cursor the client altered or kept from another version: the caller answers INVALID_CURSOR.
            log.debug("query_cursor_unreadable error={}", e.toString());
            return null;
        }
    }
}
