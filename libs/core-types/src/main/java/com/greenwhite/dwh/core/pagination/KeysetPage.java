package com.greenwhite.dwh.core.pagination;

import java.util.Collections;
import java.util.List;

/**
 * Immutable container for Keyset-paginated results.
 */
public record KeysetPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore,
        long totalEstimated
) {
    public KeysetPage {
        items = items == null ? List.of() : Collections.unmodifiableList(items);
    }

    public static <T> KeysetPage<T> of(List<T> items, String nextCursor, boolean hasMore, long totalEstimated) {
        return new KeysetPage<>(items, nextCursor, hasMore, totalEstimated);
    }

    public static <T> KeysetPage<T> empty() {
        return new KeysetPage<>(List.of(), null, false, 0);
    }
}
