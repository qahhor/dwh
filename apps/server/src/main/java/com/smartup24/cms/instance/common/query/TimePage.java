package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.CursorUtils;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.error.ApiException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Pages of a collection outside the field registry, ordered by a time and the row id (plan 10/10, item 3.5): the
 * notification inbox, the comments of a task, the announcements an administrator manages. The client sends
 * {@code limit} (1 to the collection's maximum, else 422) and the {@code nextCursor} of the previous page; the cursor
 * is opaque. The repository reads {@code limit + 1} rows after {@link #after()} to learn whether more follow.
 *
 * @param limit rows on the page
 * @param after the position after which the page starts; null for the first page
 */
public record TimePage(int limit, @Nullable Position after) {

    /** The last row of a page: its time and id, in the order of the collection. */
    public record Position(Instant at, long id) {}

    /**
     * The request of a page: {@code limit} checked against {@code max} (null means {@code defaultLimit}), the cursor
     * decoded; a bad value of either is 422 with the field named, as the registry lists answer.
     */
    public static TimePage of(@Nullable Integer limit, @Nullable String cursor, int defaultLimit, int max) {
        int size = limit == null ? defaultLimit : limit;
        if (size < 1 || size > max) {
            throw ApiException.validation(
                    "error.common.query_limit_invalid",
                    Map.of("max", max),
                    List.of(FieldErrorItem.keyed(
                            "limit",
                            QueryCompiler.INVALID_LIMIT,
                            "error.common.query_limit_invalid",
                            Map.of("max", max))));
        }
        return new TimePage(size, cursor == null || cursor.isBlank() ? null : decode(cursor));
    }

    /** The rows read ({@code limit + 1} at most) as a page; the total counts only this page. */
    public <T> KeysetPage<T> page(List<T> rows, Function<T, Position> position) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? rows.subList(0, limit) : rows;
        String next = hasMore ? encode(position.apply(items.getLast())) : null;
        return KeysetPage.of(items, next, hasMore, items.size());
    }

    static String encode(Position position) {
        return CursorUtils.encode(position.at() + "|" + position.id());
    }

    private static Position decode(String cursor) {
        String raw = CursorUtils.decode(cursor);
        int bar = raw == null ? -1 : raw.indexOf('|');
        if (bar <= 0) {
            throw invalidCursor();
        }
        try {
            return new Position(Instant.parse(raw.substring(0, bar)), Long.parseLong(raw.substring(bar + 1)));
        } catch (DateTimeParseException | NumberFormatException notOurs) {
            throw invalidCursor();
        }
    }

    private static ApiException invalidCursor() {
        return ApiException.validation(
                "error.common.query_cursor_invalid",
                List.of(FieldErrorItem.keyed(
                        "cursor", QueryCompiler.INVALID_CURSOR, "error.common.query_cursor_invalid")));
    }
}
