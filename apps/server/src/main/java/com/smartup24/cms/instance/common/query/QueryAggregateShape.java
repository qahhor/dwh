package com.smartup24.cms.instance.common.query;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The analytic shape of a report (ADR-0044): a pivot of its two groupings, the totals of its groups, and a comparison
 * of the current period with an earlier one. Every figure stays an aggregate of one SQL statement under the reader's
 * scope; the server only divides and subtracts what the statement returned.
 *
 * @param pivot   the first grouping makes the rows, the second the columns; rows, columns and the whole get totals
 * @param totals  the total of every group is returned with the rows (always with a pivot or a share)
 * @param compare the two periods compared, or null
 */
public record QueryAggregateShape(
        boolean pivot, boolean totals, @Nullable Comparison compare) {

    /** A report of groups and nothing more. */
    public static final QueryAggregateShape FLAT = new QueryAggregateShape(false, false, null);

    /** The columns a pivot shows at most; a wider pivot is refused, never cut silently. */
    public static final int MAX_COLUMNS = 50;

    /** The filled cells a pivot reads at most; a larger one is refused with a request to narrow it. */
    public static final int MAX_CELLS = 10_000;

    /** The calendar period compared: this month, quarter or year, the year to date, or a range the report names. */
    public enum Period {
        MONTH,
        QUARTER,
        YEAR,
        YTD,
        CUSTOM;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Period> fromWire(String text) {
            for (Period period : values()) {
                if (period.wire().equals(text)) return Optional.of(period);
            }
            return Optional.empty();
        }
    }

    /** What the current period is compared with: the period just before it, or the same period a year earlier. */
    public enum Against {
        PREVIOUS,
        YEAR;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Against> fromWire(String text) {
            for (Against against : values()) {
                if (against.wire().equals(text)) return Optional.of(against);
            }
            return Optional.empty();
        }
    }

    /**
     * Two periods of one date or moment field.
     *
     * @param field    the field whose value places a record in a period
     * @param period   how the current period was chosen
     * @param against  how the earlier period was chosen
     * @param current  the current period
     * @param previous the period it is compared with
     */
    public record Comparison(QueryField field, Period period, Against against, Range current, Range previous) {
        public Comparison {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(against, "against");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(previous, "previous");
        }
    }

    /**
     * A period: its first and last day, both included, and the bounds its values are compared with — the first day and
     * the day after the last for a date, the start of those days in the reader's time zone for a moment.
     *
     * @param from  the inclusive lower bound, a {@link LocalDate} or an {@link java.time.OffsetDateTime}
     * @param until the exclusive upper bound, of the same type
     */
    public record Range(LocalDate first, LocalDate last, Object from, Object until) {
        public Range {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(last, "last");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(until, "until");
            if (last.isBefore(first)) throw new IllegalArgumentException("A period ends after it starts");
        }
    }
}
