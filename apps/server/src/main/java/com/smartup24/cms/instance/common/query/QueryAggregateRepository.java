package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.jdbc.StatementTimeouts;
import com.smartup24.cms.instance.common.query.QueryAggregate.Group;
import com.smartup24.cms.instance.common.query.QueryAggregate.Measure;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.GroupColumn;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.MeasureColumn;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.Row;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Runs a {@link QueryAggregate} (ADR-0032, 10.2): one grouped query over the list's {@code from}, the data scope of the
 * viewer passed as a ready predicate in the same SQL (ADR-0013), at most {@value QueryAggregate#MAX_ROWS} groups. A
 * report reads the working database, so every statement of it is held to {@link #LIMIT}: a report that takes longer
 * is refused with a request to narrow it, and other people's requests are not kept waiting.
 */
@Repository
public class QueryAggregateRepository {

    /** The time one report may take in the database. */
    public static final Duration LIMIT = Duration.ofSeconds(5);

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
        List<Row> rows;
        try {
            rows = StatementTimeouts.within(
                    jdbc,
                    limit,
                    () -> jdbc.sql(sql.sql())
                            .params(sql.params())
                            .query((rs, rowNum) -> row(rs, aggregate))
                            .list());
        } catch (DataAccessException e) {
            if (!cancelled(e)) throw e;
            throw ApiException.validation(
                    "error.common.report_too_slow", Map.of("seconds", Math.max(1, limit.toSeconds())), List.of());
        }
        boolean truncated = rows.size() > QueryAggregate.MAX_ROWS;
        return new QueryAggregateResult(
                aggregate.groups().stream()
                        .map(group -> new GroupColumn(
                                group.field().key(),
                                group.trunc() == null ? null : group.trunc().wire(),
                                group.implicit()))
                        .toList(),
                aggregate.measures().stream()
                        .map(measure -> new MeasureColumn(measure.op().wire(), measure.fieldKey()))
                        .toList(),
                truncated ? rows.subList(0, QueryAggregate.MAX_ROWS) : rows,
                truncated);
    }

    private static Row row(ResultSet rs, QueryAggregate aggregate) throws SQLException {
        List<@Nullable Object> groups = new ArrayList<>();
        for (int i = 0; i < aggregate.groups().size(); i++) {
            groups.add(groupValue(
                    rs, QueryAggregate.groupColumn(i), aggregate.groups().get(i)));
        }
        List<@Nullable Object> values = new ArrayList<>();
        for (int i = 0; i < aggregate.measures().size(); i++) {
            String column = QueryAggregate.measureColumn(i);
            Measure measure = aggregate.measures().get(i);
            if (measure.op() == QueryAggregate.Op.COUNT) {
                values.add(rs.getLong(column));
            } else {
                values.add(rs.getBigDecimal(column));
            }
        }
        return new Row(groups, values);
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
