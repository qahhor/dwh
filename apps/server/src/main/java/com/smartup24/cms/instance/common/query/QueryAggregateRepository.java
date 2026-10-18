package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.jdbc.StatementTimeouts;
import com.smartup24.cms.instance.common.query.QueryAggregate.Group;
import com.smartup24.cms.instance.common.query.QueryAggregate.Measure;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateGroup;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateRow;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateTotal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Runs a {@link QueryAggregate} (ADR-0032, 10.2): one grouped query over the list's {@code from}, the data scope of the
 * viewer passed as a ready predicate in the same SQL (ADR-0013), at most {@value QueryAggregate#MAX_ROWS} groups — or the
 * top the report asks for, in its order. A
 * report reads the working database, so every statement of it is held to {@link #LIMIT}: a report that takes longer
 * is refused with a request to narrow it, and other people's requests are not kept waiting.
 */
@Repository
public class QueryAggregateRepository {

    /** The time one report may take in the database. */
    public static final Duration LIMIT = Duration.ofSeconds(5);

    /** The kinds of a total (ADR-0044): of a pivot's row, of its column, of every group. */
    public static final String ROW = "row";

    public static final String COLUMN = "column";
    public static final String GRAND = "grand";

    /** The SQL state of a statement cancelled by its time limit. */
    private static final String QUERY_CANCELED = "57014";

    private final JdbcClient jdbc;
    private final Duration limit;

    @Autowired
    public QueryAggregateRepository(JdbcClient jdbc) {
        this(jdbc, LIMIT);
    }

    private QueryAggregateRepository(JdbcClient jdbc, Duration limit) {
        this.jdbc = jdbc;
        this.limit = limit;
    }

    /** A repository whose reports may take {@code limit}: for tests of the refusal. */
    public static QueryAggregateRepository limitedTo(JdbcClient jdbc, Duration limit) {
        return new QueryAggregateRepository(jdbc, limit);
    }

    /**
     * The report's rows for the viewer; runs inside the caller's transaction.
     *
     * @param extra the data scope ({@code " and ..."}) with its parameters, not prefixed {@code q_}
     * @throws ApiException 422 {@code error.common.report_too_slow} when the report takes longer than its limit
     */
    public QueryAggregateResult run(QueryAggregate aggregate, QueryPlan.SqlFragment extra) {
        QueryPlan.SqlFragment sql = aggregate.sql(extra);
        List<Read> read;
        try {
            read = StatementTimeouts.within(
                    jdbc,
                    limit,
                    () -> jdbc.sql(sql.sql())
                            .params(sql.params())
                            .query((rs, rowNum) -> read(rs, aggregate))
                            .list());
        } catch (DataAccessException e) {
            if (!cancelled(e)) throw e;
            throw ApiException.validation(
                    "error.common.report_too_slow", Map.of("seconds", Math.max(1, limit.toSeconds())), List.of());
        }
        List<AggregateRow> rows = new ArrayList<>();
        List<AggregateTotal> totals = new ArrayList<>();
        for (Read item : read) {
            if (item.total() == 0) rows.add(item.row());
            else totals.add(AggregateTotal.of(kind(aggregate, item.total()), item.row()));
        }
        int cap = aggregate.order().cap();
        boolean truncated = !aggregate.shape().pivot() && rows.size() > cap;
        return new QueryAggregateResult(
                aggregate.groups().stream()
                        .map(group -> new AggregateGroup(
                                group.field().key(),
                                group.trunc() == null ? null : group.trunc().wire(),
                                group.implicit()))
                        .toList(),
                QueryAggregateFinish.columns(aggregate),
                truncated ? rows.subList(0, cap) : rows,
                truncated,
                totals,
                aggregate.shape().pivot(),
                QueryAggregateFinish.comparison(aggregate));
    }

    /** A row of the statement: a group, or a total with the bits of the groupings it adds up. */
    private record Read(int total, AggregateRow row) {}

    /** A pivot's total of a row keeps its first grouping, of a column its second; any other total is the whole. */
    private static String kind(QueryAggregate aggregate, int bits) {
        if (!aggregate.shape().pivot()) return GRAND;
        return switch (bits) {
            case 1 -> ROW;
            case 2 -> COLUMN;
            default -> GRAND;
        };
    }

    private static Read read(ResultSet rs, QueryAggregate aggregate) throws SQLException {
        List<@Nullable Object> groups = new ArrayList<>();
        for (int i = 0; i < aggregate.groups().size(); i++) {
            groups.add(groupValue(
                    rs, QueryAggregate.groupColumn(i), aggregate.groups().get(i)));
        }
        boolean compared = aggregate.shape().compare() != null;
        List<@Nullable Object> values = new ArrayList<>();
        for (int i = 0; i < aggregate.measures().size(); i++) {
            Measure measure = aggregate.measures().get(i);
            Object current = measureValue(rs, QueryAggregate.measureColumn(i), measure);
            if (compared) {
                Object previous = measureValue(rs, QueryAggregate.previousColumn(i), measure);
                values.addAll(QueryAggregateFinish.periods(measure, current, previous));
            } else {
                values.add(current);
            }
        }
        int total = aggregate.totaled() ? rs.getInt("q_t") : 0;
        return new Read(total, new AggregateRow(groups, values, Collections.nCopies(groups.size(), null)));
    }

    private static @Nullable Object measureValue(ResultSet rs, String column, Measure measure) throws SQLException {
        return measure.counted() ? Long.valueOf(rs.getLong(column)) : rs.getBigDecimal(column);
    }

    private static @Nullable Object groupValue(ResultSet rs, String column, Group group) throws SQLException {
        if (group.trunc() != null) {
            Date date = rs.getDate(column);
            return date == null ? null : date.toLocalDate().toString();
        }
        return switch (group.field().type()) {
            case BOOLEAN -> {
                boolean flag = rs.getBoolean(column);
                yield rs.wasNull() ? null : flag;
            }
            case NUMBER -> {
                long key = rs.getLong(column);
                yield rs.wasNull() ? null : key;
            }
            default -> rs.getString(column);
        };
    }

    /** Whether the failure is the time limit cancelling the statement. */
    private static boolean cancelled(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
