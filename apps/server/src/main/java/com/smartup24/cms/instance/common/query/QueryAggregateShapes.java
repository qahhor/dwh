package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.query.QueryAggregate.Group;
import com.smartup24.cms.instance.common.query.QueryAggregate.Measure;
import com.smartup24.cms.instance.common.query.QueryAggregate.Share;
import com.smartup24.cms.instance.common.query.QueryAggregateShape.Against;
import com.smartup24.cms.instance.common.query.QueryAggregateShape.Comparison;
import com.smartup24.cms.instance.common.query.QueryAggregateShape.Period;
import com.smartup24.cms.instance.common.query.QueryAggregateShape.Range;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Parses the analytic shape of a report (ADR-0044) and checks it against its groups and measures:
 * <pre>pivot   true                      exactly two groupings: rows, then columns
 * totals  true                      the totals of the groups
 * compare {"field":"date","period":"month|quarter|year|ytd|custom","against":"previous|year",
 *          "from":"2026-01-01","to":"2026-03-31"}   from and to only for custom</pre>
 * A share of a row or a column needs a pivot; a comparison takes no pivot and no share, and its field is not a grouping.
 * Errors point at {@code pivot}, {@code totals}, {@code compare.period}, {@code measures[1].share}.
 */
final class QueryAggregateShapes {
    private static final Set<String> COMPARE_KEYS = Set.of("field", "period", "against", "from", "to");

    /** The longest custom period: ten years of days. */
    private static final long MAX_DAYS = 3660;

    private QueryAggregateShapes() {}

    static QueryAggregateShape shape(
            QueryList list,
            QueryAggregateSpec spec,
            List<Group> groups,
            List<Measure> measures,
            QueryAggregateSpec.Anchor anchor,
            List<FieldErrorItem> errors) {
        boolean pivot = flag(spec.pivot(), "pivot", errors);
        boolean totals = flag(spec.totals(), "totals", errors);
        if (pivot && groups.size() != 2) {
            errors.add(invalid("pivot", "error.common.field_report_pivot_groups"));
        }
        Comparison compare = null;
        JsonNode node = spec.compare();
        if (node != null && !node.isNull()) {
            compare = comparison(list, node, anchor, errors).orElse(null);
            if (pivot) errors.add(invalid("compare", "error.common.field_report_compare_pivot"));
            String key = compare == null ? null : compare.field().key();
            if (groups.stream().anyMatch(group -> group.field().key().equals(key))) {
                errors.add(invalid("compare.field", "error.common.field_report_compare_grouped"));
            }
        }
        for (int i = 0; i < measures.size(); i++) {
            Share share = measures.get(i).share();
            if (share == null) continue;
            if (node != null && !node.isNull()) {
                errors.add(invalid("measures[" + i + "].share", "error.common.field_report_share_compare"));
            } else if (share != Share.TOTAL && !pivot) {
                errors.add(invalid("measures[" + i + "].share", "error.common.field_report_share_pivot"));
            }
        }
        return new QueryAggregateShape(pivot, totals, compare);
    }

    private static boolean flag(@Nullable JsonNode node, String at, List<FieldErrorItem> errors) {
        if (node == null || node.isNull()) return false;
        if (node.isBoolean()) return node.booleanValue();
        errors.add(invalid(at, "error.common.field_report_flag_invalid"));
        return false;
    }

    private static Optional<Comparison> comparison(
            QueryList list, JsonNode node, QueryAggregateSpec.Anchor anchor, List<FieldErrorItem> errors) {
        if (!node.isObject()) {
            errors.add(invalid("compare", "error.common.field_report_compare_invalid"));
            return Optional.empty();
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!COMPARE_KEYS.contains(entry.getKey())) {
                errors.add(FieldErrorItem.keyed(
                        "compare." + entry.getKey(), QueryAggregates.REPORT_INVALID, "error.field.unknown_key"));
                return Optional.empty();
            }
        }
        Optional<QueryField> field = text(node, "field")
                .flatMap(list::viewerField)
                .filter(QueryField::filterable)
                .filter(found -> found.type() == QueryFieldType.DATE || found.type() == QueryFieldType.INSTANT);
        if (field.isEmpty()) errors.add(invalid("compare.field", "error.common.field_report_compare_field"));
        Optional<Period> period = text(node, "period").flatMap(Period::fromWire);
        if (period.isEmpty()) errors.add(invalid("compare.period", "error.common.field_report_compare_invalid"));
        Optional<Against> against =
                node.has("against") ? text(node, "against").flatMap(Against::fromWire) : Optional.of(Against.PREVIOUS);
        if (against.isEmpty()) errors.add(invalid("compare.against", "error.common.field_report_compare_invalid"));
        boolean custom = period.orElse(null) == Period.CUSTOM;
        LocalDate from = custom ? date(node, "from", errors) : absent(node, "from", errors);
        LocalDate to = custom ? date(node, "to", errors) : absent(node, "to", errors);
        if (custom
                && from != null
                && to != null
                && (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS)) {
            errors.add(invalid("compare.to", "error.common.field_report_compare_range"));
            return Optional.empty();
        }
        if (field.isEmpty() || period.isEmpty() || against.isEmpty() || (custom && (from == null || to == null))) {
            return Optional.empty();
        }
        LocalDate[] days = days(period.get(), against.get(), anchor.today(), from, to);
        boolean moment = field.get().type() == QueryFieldType.INSTANT;
        return Optional.of(new Comparison(
                field.get(),
                period.get(),
                against.get(),
                range(days[0], days[1], moment, anchor.zone()),
                range(days[2], days[3], moment, anchor.zone())));
    }

    /**
     * The first and last days of the current period and of the one it is compared with. Month, quarter and year are
     * whole calendar periods holding {@code today}; the year to date ends today; a year earlier is the same calendar
     * period of the year before, the period just before has the same length.
     */
    static LocalDate[] days(
            Period period, Against against, LocalDate today, @Nullable LocalDate from, @Nullable LocalDate to) {
        LocalDate first = switch (period) {
            case MONTH -> today.withDayOfMonth(1);
            case QUARTER -> LocalDate.of(today.getYear(), (today.getMonthValue() - 1) / 3 * 3 + 1, 1);
            case YEAR, YTD -> today.withDayOfYear(1);
            case CUSTOM -> Objects.requireNonNull(from, "from");
        };
        LocalDate last = switch (period) {
            case MONTH -> first.plusMonths(1).minusDays(1);
            case QUARTER -> first.plusMonths(3).minusDays(1);
            case YEAR -> first.plusYears(1).minusDays(1);
            case YTD -> today;
            case CUSTOM -> Objects.requireNonNull(to, "to");
        };
        LocalDate previousFirst;
        LocalDate previousLast;
        if (against == Against.YEAR || period == Period.YEAR || period == Period.YTD) {
            previousFirst = first.minusYears(1);
            previousLast = switch (period) {
                case MONTH -> previousFirst.plusMonths(1).minusDays(1);
                case QUARTER -> previousFirst.plusMonths(3).minusDays(1);
                case YEAR -> previousFirst.plusYears(1).minusDays(1);
                case YTD, CUSTOM -> last.minusYears(1);
            };
        } else {
            previousLast = first.minusDays(1);
            previousFirst = switch (period) {
                case MONTH -> first.minusMonths(1);
                case QUARTER -> first.minusMonths(3);
                default -> first.minusDays(ChronoUnit.DAYS.between(first, last) + 1);
            };
        }
        return new LocalDate[] {first, last, previousFirst, previousLast};
    }

    private static Range range(LocalDate first, LocalDate last, boolean moment, ZoneId zone) {
        LocalDate after = last.plusDays(1);
        return moment
                ? new Range(
                        first,
                        last,
                        first.atStartOfDay(zone).toOffsetDateTime(),
                        after.atStartOfDay(zone).toOffsetDateTime())
                : new Range(first, last, first, after);
    }

    private static Optional<String> text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isString() ? Optional.of(value.asString()) : Optional.empty();
    }

    private static @Nullable LocalDate date(JsonNode node, String key, List<FieldErrorItem> errors) {
        Optional<String> text = text(node, key);
        try {
            LocalDate day = text.map(LocalDate::parse).orElse(null);
            if (day != null && day.getYear() >= 1 && day.getYear() < 9999) return day;
        } catch (DateTimeParseException e) {
            errors.add(invalid("compare." + key, "error.common.field_report_compare_range"));
            return null;
        }
        errors.add(invalid("compare." + key, "error.common.field_report_compare_range"));
        return null;
    }

    private static @Nullable LocalDate absent(JsonNode node, String key, List<FieldErrorItem> errors) {
        JsonNode value = node.get(key);
        if (value != null && !value.isNull()) {
            errors.add(invalid("compare." + key, "error.common.field_report_compare_range"));
        }
        return null;
    }

    private static FieldErrorItem invalid(String at, String key) {
        return FieldErrorItem.keyed(at, QueryAggregates.REPORT_INVALID, key);
    }
}
