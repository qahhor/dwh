package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryOp;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.md.service.MdUserQuery;
import com.smartup24.cms.instance.support.TestDatabases;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Plan 10/10, item 3.7: a filter on a custom field is served by the GIN index of {@code attributes}. Equality is
 * written as containment ({@code attributes @> {"code": value}}); the former {@code attributes->>'code' = ?} could
 * not use any index. With sequential scans switched off the plan shows whether the predicate is indexable at all.
 */
class CustomFieldFilterIndexTest {

    private static final QueryField REGION = QueryField.custom(
            "cfRegion", "Region", QueryFieldType.TEXT, "(md_users.attributes->>'region')", "region", List.of());

    @Test
    @DisplayName("3.7: equality and a list of values on a custom field use the GIN index and find the same rows")
    void customFieldEqualityUsesTheGinIndex() throws Exception {
        DataSource database = TestDatabases.migratedCopy("cf_filter");
        JdbcClient setup = JdbcClient.create(database);
        setup.sql("""
                insert into md_users (name, login, email, attributes)
                select 'User ' || g, 'cf-user-' || g, 'cf-user-' || g || '@example.test',
                       jsonb_build_object('region', 'r' || (g % 50))
                from generate_series(1, 3000) g
                """).update();
        setup.sql("analyze md_users").update();

        try (Connection connection = database.getConnection()) {
            JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            jdbc.sql("set enable_seqscan = off").update();

            QueryPlan.SqlFragment equal = where(QueryOp.EQ, List.of("r7"));
            assertThat(explain(jdbc, equal)).contains("md_users_attributes_gin_idx");
            assertThat(count(jdbc, equal)).isEqualTo(60);

            QueryPlan.SqlFragment anyOf = where(QueryOp.IN, List.of("r7", "r8"));
            assertThat(explain(jdbc, anyOf)).contains("md_users_attributes_gin_idx");
            assertThat(count(jdbc, anyOf)).isEqualTo(120);

            String former = String.join(
                    "\n",
                    jdbc.sql("explain select md_users.id from md_users where (md_users.attributes->>'region') = 'r7'")
                            .query(String.class)
                            .list());
            assertThat(former).doesNotContain("md_users_attributes_gin_idx");
        }
    }

    private static QueryPlan.SqlFragment where(QueryOp op, List<Object> values) {
        QueryField sort = MdUserQuery.LIST.fields().getFirst();
        var plan = new QueryPlan(
                MdUserQuery.LIST,
                List.of(new QueryPlan.Condition(REGION, op, values)),
                sort,
                false,
                20,
                null,
                "test",
                null,
                Set.of());
        return plan.where();
    }

    private static String explain(JdbcClient jdbc, QueryPlan.SqlFragment where) {
        return String.join(
                "\n",
                jdbc.sql("explain select md_users.id from md_users where true" + where.sql())
                        .params(where.params())
                        .query(String.class)
                        .list());
    }

    private static long count(JdbcClient jdbc, QueryPlan.SqlFragment where) {
        return jdbc.sql("select count(*) from md_users where true" + where.sql())
                .params(where.params())
                .query(Long.class)
                .single();
    }
}
