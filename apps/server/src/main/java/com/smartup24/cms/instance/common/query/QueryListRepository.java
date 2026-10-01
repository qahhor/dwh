package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.pagination.KeysetPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Runs a {@link QueryPlan}: a page by keyset cursor and the total on the first page.
 * The module passes the data scope (ADR-0013) as a ready {@code extra} predicate that goes into the same SQL.
 */
@Repository
public class QueryListRepository {

    private static final String SORT_COLUMN = "q_sort_value";
    private static final String ID_COLUMN = "q_row_id";
    /** Under this many estimated rows the first page counts them: the count is cheap and exact. */
    private static final long EXACT_BELOW = 100_000;
    /** The first "Plan Rows" of EXPLAIN (FORMAT JSON) belongs to the top node: the rows of the whole query. */
    private static final Pattern PLAN_ROWS = Pattern.compile("\"Plan Rows\":\\s*([0-9.eE+]+)");

    private final JdbcClient jdbc;
    private final long exactBelow;

    @Autowired
    public QueryListRepository(JdbcClient jdbc) {
        this(jdbc, EXACT_BELOW);
    }

    private QueryListRepository(JdbcClient jdbc, long exactBelow) {
        this.jdbc = jdbc;
        this.exactBelow = exactBelow;
    }

    /** A repository that trusts the planner's estimate from {@code exactBelow} rows up: for tests on small tables. */
    public static QueryListRepository estimatingFrom(JdbcClient jdbc, long exactBelow) {
        return new QueryListRepository(jdbc, exactBelow);
    }

    public <T> KeysetPage<T> page(QueryPlan plan, RowMapper<T> mapper) {
        return page(plan, mapper, new QueryPlan.SqlFragment("", Map.of()));
    }

    /** @param extra an additional module predicate ({@code " and ..."}) with parameters not prefixed {@code q_} */
    public <T> KeysetPage<T> page(QueryPlan plan, RowMapper<T> mapper, QueryPlan.SqlFragment extra) {
        QueryList list = plan.list();
        QueryPlan.SqlFragment where = plan.where();
        QueryPlan.SqlFragment keyset = plan.keyset();
        String sql = "select " + list.select() + ", " + plan.sort().sql() + " as " + SORT_COLUMN + ", "
                + list.idSql() + " as " + ID_COLUMN
                + " from " + list.from()
                + " where 1=1" + extra.sql() + where.sql() + keyset.sql()
                + plan.orderBy()
                + " limit :q_limit";
        Map<String, Object> params = new LinkedHashMap<>(extra.params());
        params.putAll(where.params());
        params.putAll(keyset.params());
        params.put("q_limit", plan.limit() + 1);

        List<Row<T>> rows = new ArrayList<>(jdbc.sql(sql)
                .params(params)
                .query((rs, rowNum) -> new Row<>(
                        mapper.mapRow(rs, rowNum),
                        QueryValues.read(rs, SORT_COLUMN, plan.sort().type()),
                        rs.getString(ID_COLUMN)))
                .list());

        boolean hasMore = rows.size() > plan.limit();
        List<Row<T>> page = rows.subList(0, Math.min(rows.size(), plan.limit()));
        // A first page that holds the whole result is its own count, whatever the list.
        boolean exact = !list.estimatedTotal() || (plan.cursor() == null && !hasMore);
        long total;
        if (plan.cursor() != null) {
            total = plan.cursor().total();
        } else if (!hasMore) {
            total = page.size();
        } else if (!list.estimatedTotal()) {
            total = count(plan, extra, where);
        } else {
            long estimated = estimate(plan, extra, where);
            // Below the threshold a count is cheap, and an estimate from missing or stale statistics can be far off
            // (thousands for a table that holds a hundred rows).
            if (estimated < exactBelow) {
                total = count(plan, extra, where);
                exact = true;
            } else {
                total = Math.max(estimated, rows.size());
            }
        }
        String next = null;
        if (hasMore && !page.isEmpty()) {
            Row<T> last = page.getLast();
            next = new QueryCursor(plan.fingerprint(), last.sortValue(), last.id(), total).encode(plan.sort());
        }
        return new KeysetPage<>(page.stream().map(Row::item).toList(), next, hasMore, total, exact);
    }

    private long count(QueryPlan plan, QueryPlan.SqlFragment extra, QueryPlan.SqlFragment where) {
        Map<String, Object> params = new LinkedHashMap<>(extra.params());
        params.putAll(where.params());
        return jdbc.sql("select count(*) from " + plan.list().from() + " where 1=1" + extra.sql() + where.sql())
                .params(params)
                .query(Long.class)
                .single();
    }

    /**
     * The planner's estimate of the rows (plan 10/10, item 3.5): planning costs the same on a thousand rows and on
     * fifty million, where a count reads every partition. The estimate follows the statistics, so it is approximate.
     */
    private long estimate(QueryPlan plan, QueryPlan.SqlFragment extra, QueryPlan.SqlFragment where) {
        Map<String, Object> params = new LinkedHashMap<>(extra.params());
        params.putAll(where.params());
        String explained = String.join(
                " ",
                jdbc.sql("explain (format json) select 1 from " + plan.list().from() + " where 1=1" + extra.sql()
                                + where.sql())
                        .params(params)
                        .query(String.class)
                        .list());
        Matcher rows = PLAN_ROWS.matcher(explained);
        return rows.find() ? Math.round(Double.parseDouble(rows.group(1))) : 0L;
    }

    private record Row<T>(T item, Object sortValue, String id) {}
}
