package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.Locale;

/** Input rules of a search request; each violation is a 400 with its own message key. */
final class SearchRequestRules {

    private SearchRequestRules() {}

    static int effectiveLimit(int requestedLimit, SearchQueryPolicy queryPolicy) {
        validateLimit(requestedLimit);
        return Math.min(requestedLimit, queryPolicy.globalLimit());
    }

    static void validateLimit(Integer requestedLimit) {
        if (requestedLimit != null && (requestedLimit < 1 || requestedLimit > 50)) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.limit_range");
        }
    }

    static String normalizeQuery(String query) {
        if (query == null) throw invalidQuery();
        String clean = query.trim();
        int length = clean.codePointCount(0, clean.length());
        if (length < 2 || length > 200) throw invalidQuery();
        return clean;
    }

    private static ApiException invalidQuery() {
        return ApiException.badRequest(ErrorCode.EMPTY_QUERY, "error.search.query_length");
    }

    static String normalizeEntityType(String entityType) {
        if (entityType == null) return "ALL";
        String normalized = entityType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ALL", "TASK", "PROJECT", "USER", "NOTE" -> normalized;
            default -> throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.category_unknown");
        };
    }

    /** The id of an exact "#123" query, or null for a text query. */
    static Long exactId(String query) {
        if (!query.matches("#[0-9]+")) return null;
        try {
            long id = Long.parseLong(query.substring(1));
            if (id <= 0) throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.id_not_positive");
            return id;
        } catch (NumberFormatException overflow) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.id_invalid");
        }
    }
}
