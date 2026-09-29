package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.pagination.KeysetPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Выполняет {@link QueryPlan}: страница по keyset-курсору и итог на первой странице.
 * Скоуп данных (ADR-0013) модуль передаёт готовым предикатом {@code extra}: он ложится в тот же SQL.
 */
@Repository
public class QueryListRepository {

    private static final String SORT_COLUMN = "q_sort_value";
    private static final String ID_COLUMN = "q_row_id";
    /** The first "Plan Rows" of EXPLAIN (FORMAT JSON) belongs to the top node: the rows of the whole query. */
    private static final Pattern PLAN_ROWS = Pattern.compile("\"Plan Rows\":\\s*([0-9.eE+]+)");

    private final JdbcClient jdbc;

    public QueryListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public <T> KeysetPage<T> page(QueryPlan plan, RowMapper<T> mapper) {
        return page(plan, mapper, new QueryPlan.SqlFragment("", Map.of()));
    }

    /** @param extra дополнительный предикат модуля ({@code " and ..."}) с параметрами без префикса {@code q_} */
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
        } else {
            total = list.estimatedTotal()
                    ? Math.max(estimate(plan, extra, where), rows.size())
                    : count(plan, extra, where);
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
