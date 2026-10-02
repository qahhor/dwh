package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Input rules of a search request; each violation is a 400 with its own message key. */
final class SearchRequestRules {

    /** Every entity the caller may search. */
    static final String ALL = "ALL";

    private static final Pattern ENTITY = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

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

    /** {@code ALL}, or the code of an entity ({@code ms.tasks}, ADR-0032, 10.3); anything else is a 400. */
    static String normalizeEntityType(String entityType) {
        if (entityType == null) return ALL;
        String trimmed = entityType.trim();
        if (ALL.equals(trimmed.toUpperCase(Locale.ROOT))) return ALL;
        if (!ENTITY.matcher(trimmed).matches()) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.category_unknown");
        }
        return trimmed;
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
