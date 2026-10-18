package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.instance.common.web.RequestZone;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What a report asks for, as JSON trees (ADR-0032, 10.2; ADR-0044): the groups, the measures and the filter, the order
 * of the groups and the top kept, and its analytic shape — a pivot, the totals and a period comparison.
 *
 * <pre>{"groupBy": [{"field": "region"}, {"field": "date", "trunc": "month"}],
 *  "measures": [{"op": "sum", "field": "total"}, {"op": "sum", "field": "total", "share": "total"}],
 *  "pivot": true, "totals": true,
 *  "compare": {"field": "date", "period": "month", "against": "previous"}}</pre>
 *
 * @param filter the list's filter DSL as JSON text
 */
public record QueryAggregateSpec(
        @Nullable JsonNode groupBy,
        @Nullable JsonNode measures,
        @Nullable String filter,
        @Nullable JsonNode sort,
        @Nullable JsonNode limit,
        @Nullable JsonNode pivot,
        @Nullable JsonNode totals,
        @Nullable JsonNode compare,
        List<QueryComputedMeasure> computed) {

    public QueryAggregateSpec {
        computed = List.copyOf(computed);
    }

    /** A report without measures computed by formulas. */
    public QueryAggregateSpec(
            @Nullable JsonNode groupBy,
            @Nullable JsonNode measures,
            @Nullable String filter,
            @Nullable JsonNode sort,
            @Nullable JsonNode limit,
            @Nullable JsonNode pivot,
            @Nullable JsonNode totals,
            @Nullable JsonNode compare) {
        this(groupBy, measures, filter, sort, limit, pivot, totals, compare, List.of());
    }

    /** The same report able to name the measures a dataset computes by formulas (ADR-0044). */
    public QueryAggregateSpec withComputed(List<QueryComputedMeasure> measures) {
        return new QueryAggregateSpec(groupBy, measures(), filter, sort, limit, pivot, totals, compare, measures);
    }

    /** A flat report: groups, measures and a filter, ordered and cut as asked. */
    public static QueryAggregateSpec flat(
            @Nullable JsonNode groupBy,
            @Nullable JsonNode measures,
            @Nullable String filter,
            @Nullable JsonNode sort,
            @Nullable JsonNode limit) {
        return new QueryAggregateSpec(groupBy, measures, filter, sort, limit, null, null, null);
    }

    /** The report a saved state describes, with the filter to run it with (the state's own, or narrowed). */
    public static QueryAggregateSpec ofState(JsonNode state, @Nullable String filter) {
        return new QueryAggregateSpec(
                state.get("groupBy"),
                state.get("measures"),
                filter,
                state.get("sort"),
                state.get("limit"),
                state.get("pivot"),
                state.get("totals"),
                state.get("compare"));
    }

    /** The same report over another filter. */
    public QueryAggregateSpec withFilter(@Nullable String other) {
        return new QueryAggregateSpec(groupBy, measures, other, sort, limit, pivot, totals, compare, computed);
    }

    /**
     * When a report runs: the reader's time zone ({@code X-Time-Zone}, ADR-0042) and the day it is there — the current
     * period of a comparison and the presets of a date parameter count from it.
     */
    public record Anchor(ZoneId zone, LocalDate today) {
        public Anchor {
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(today, "today");
        }

        /** Now, in the zone of the request. */
        public static Anchor now(Clock clock) {
            ZoneId zone = RequestZone.current();
            return new Anchor(zone, LocalDate.now(clock.withZone(zone)));
        }
    }
}
