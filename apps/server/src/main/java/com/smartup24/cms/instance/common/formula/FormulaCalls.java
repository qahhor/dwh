package com.smartup24.cms.instance.common.formula;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The functions of formulas that evaluate all their arguments first (ADR-0042); {@code if} and {@code coalesce} are
 * evaluated by {@link FormulaEvaluator}. A null argument gives a null result, except where a function says otherwise:
 * {@code min}/{@code max} pass over nulls, {@code isEmpty} answers about it, {@code len} counts no characters and
 * {@code contains} answers false. Dates stay within the years 1 to 9999 ({@code invalid_date}); a count of days,
 * months or digits is a whole number ({@code invalid_argument}).
 */
final class FormulaCalls {
    private static final LocalDate FIRST = LocalDate.of(1, 1, 1);
    private static final LocalDate LAST = LocalDate.of(9999, 12, 31);

    private FormulaCalls() {}

    static @Nullable Object apply(String name, List<@Nullable Object> args, FormulaScope scope) {
        switch (name) {
            case "isEmpty" -> {
                return FormulaFunctions.empty(args.getFirst());
            }
            case "len" -> {
                return BigDecimal.valueOf(FormulaTexts.length(args.getFirst()));
            }
            case "contains" -> {
                return FormulaTexts.contains(args.getFirst(), args.get(1));
            }
            case "min", "max" -> {
                return extreme("max".equals(name), args);
            }
            case "sum" -> {
                return sum(args.getFirst());
            }
            case "count" -> {
                return BigDecimal.valueOf(filled(args.getFirst()).size());
            }
            case "today" -> {
                return LocalDate.ofInstant(scope.now(), scope.zone());
            }
            case "now" -> {
                return scope.now().truncatedTo(ChronoUnit.SECONDS);
            }
            default -> {
                if (args.contains(null)) return null;
                return strict(name, args, scope);
            }
        }
    }

    /** The functions that need every argument. */
    private static @Nullable Object strict(String name, List<@Nullable Object> args, FormulaScope scope) {
        Object first = args.getFirst();
        return switch (name) {
            case "round" ->
                FormulaNumbers.round(
                        (BigDecimal) first, args.size() > 1 ? FormulaNumbers.whole((BigDecimal) args.get(1)) : 0);
            case "abs" -> ((BigDecimal) first).abs();
            case "upper" -> FormulaTexts.upper(first);
            case "lower" -> FormulaTexts.lower(first);
            case "trim" -> FormulaTexts.trim(first);
            case "date" -> date(args);
            case "dateOf" -> LocalDate.ofInstant((Instant) first, scope.zone());
            case "addDays" -> bounded(((LocalDate) first).plusDays(FormulaNumbers.whole((BigDecimal) args.get(1))));
            case "addMonths" -> bounded(((LocalDate) first).plusMonths(FormulaNumbers.whole((BigDecimal) args.get(1))));
            case "daysBetween" ->
                BigDecimal.valueOf(ChronoUnit.DAYS.between((LocalDate) first, (LocalDate) args.get(1)));
            default -> part(name, first, scope);
        };
    }

    /** {@code year}, {@code month}, {@code day}, {@code weekday} (Monday 1), {@code hour}, {@code minute}. */
    private static BigDecimal part(String name, Object value, FormulaScope scope) {
        ZonedDateTime moment = value instanceof Instant instant ? instant.atZone(scope.zone()) : null;
        LocalDate date = moment != null ? moment.toLocalDate() : value instanceof LocalDate day ? day : null;
        LocalTime time = moment != null ? moment.toLocalTime() : value instanceof LocalTime clock ? clock : null;
        int part = switch (name) {
            case "year" -> required(date).getYear();
            case "month" -> required(date).getMonthValue();
            case "day" -> required(date).getDayOfMonth();
            case "weekday" -> required(date).getDayOfWeek().getValue();
            case "hour" -> required(time).getHour();
            default -> required(time).getMinute();
        };
        return BigDecimal.valueOf(part);
    }

    private static <T> T required(@Nullable T value) {
        if (value == null) throw new FormulaFailure("invalid_argument");
        return value;
    }

    private static @Nullable Object extreme(boolean max, List<@Nullable Object> args) {
        Object best = null;
        for (Object value : args) {
            if (value == null) continue;
            if (best == null) {
                best = value;
                continue;
            }
            int order = FormulaEvaluator.compare(value, best);
            if (max ? order > 0 : order < 0) best = value;
        }
        return best;
    }

    /**
     * The sum of the filled numbers of a column (ADR-0043): zero for no rows or no filled value, as a document's total
     * of no lines is zero — a rule {@code total > 0} must fail an empty document, which a null would let pass.
     */
    private static BigDecimal sum(@Nullable Object column) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object value : filled(column)) total = FormulaNumbers.add(total, (BigDecimal) value);
        return total;
    }

    /** The filled values of a list of rows: neither null nor a text of only spaces. */
    private static List<Object> filled(@Nullable Object rows) {
        if (!(rows instanceof List<?> list)) return List.of();
        List<Object> values = new ArrayList<>();
        for (Object value : list) {
            if (!FormulaFunctions.empty(value)) values.add(value);
        }
        return values;
    }

    private static LocalDate date(List<@Nullable Object> args) {
        int year = FormulaNumbers.whole((BigDecimal) required(args.getFirst()));
        int month = FormulaNumbers.whole((BigDecimal) required(args.get(1)));
        int day = FormulaNumbers.whole((BigDecimal) required(args.get(2)));
        if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31) {
            throw new FormulaFailure("invalid_date");
        }
        LocalDate first = LocalDate.of(year, month, 1);
        if (day > first.lengthOfMonth()) throw new FormulaFailure("invalid_date");
        return first.withDayOfMonth(day);
    }

    private static LocalDate bounded(LocalDate date) {
        if (date.isBefore(FIRST) || date.isAfter(LAST)) throw new FormulaFailure("invalid_date");
        return date;
    }
}
