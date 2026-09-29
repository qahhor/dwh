package com.smartup24.cms.core.pagination;

import java.util.Collections;
import java.util.List;

/**
 * Immutable container for Keyset-paginated results.
 *
 * @param totalExact {@code totalEstimated} is a count of the rows; false when it is the planner's estimate, as for a
 *                   list over a table that grows without bound (plan 10/10, item 3.5)
 */
public record KeysetPage<T>(
        List<T> items, String nextCursor, boolean hasMore, long totalEstimated, boolean totalExact) {
    public KeysetPage {
        items = items == null ? List.of() : Collections.unmodifiableList(items);
    }

    public KeysetPage(List<T> items, String nextCursor, boolean hasMore, long totalEstimated) {
        this(items, nextCursor, hasMore, totalEstimated, true);
    }

    public static <T> KeysetPage<T> of(List<T> items, String nextCursor, boolean hasMore, long totalEstimated) {
        return new KeysetPage<>(items, nextCursor, hasMore, totalEstimated);
    }

    /** A page whose total is an estimate, not a count. */
    public static <T> KeysetPage<T> estimated(List<T> items, String nextCursor, boolean hasMore, long total) {
        return new KeysetPage<>(items, nextCursor, hasMore, total, false);
    }

    public static <T> KeysetPage<T> empty() {
        return new KeysetPage<>(List.of(), null, false, 0);
    }
}
