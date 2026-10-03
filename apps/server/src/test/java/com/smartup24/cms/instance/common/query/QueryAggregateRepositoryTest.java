package com.smartup24.cms.instance.common.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 10/10, item 5.8 (ADR-0032, 10.2 and 12): a report runs as one grouped query on PostgreSQL — its values come back
 * typed, at most 1000 groups with the cut reported, and a report that takes longer than its limit is refused with a
 * request to narrow it instead of holding the database.
 */
class QueryAggregateRepositoryTest {

    private static final String ROWS = "(select g, case when g % 2 = 0 then 'even' else 'odd' end as kind,"
            + " g % 3 = 0 as third, g % 4 + 1 as ref_id, g * 1.5 as amount,"
            + " date '2024-01-01' + g as day, timestamptz '2024-01-01 23:30:00+00' + g * interval '1 day' as at"
            + " from generate_series(1, 1100) g) t";

    private static final QueryList LIST = new QueryList(
            "test.series",
            "test.form",
            "view",
            "t.g",
            ROWS,
            "t.g",
            List.of(
                    QueryField.of("g", "g", QueryFieldType.NUMBER, "t.g").asSortable(),
                    QueryField.enumeration("kind", "k", "t.kind", List.of("even", "odd"), null),
                    QueryField.of("third", "t", QueryFieldType.BOOLEAN, "t.third"),
                    QueryField.of("refId", "r", QueryFieldType.NUMBER, "t.ref_id")
                            .refersTo(QueryRef.whole("/refs", "name")),
                    QueryField.of("amount", "a", QueryFieldType.NUMBER, "t.amount"),
                    QueryField.of("day", "d", QueryFieldType.DATE, "t.day"),
                    QueryField.of("at", "i", QueryFieldType.INSTANT, "t.at")),
            "g");

    static JdbcClient jdbc;
    static TransactionTemplate tx;

    @BeforeAll
    static void setup() {
        DataSource ds = TestDatabases.migratedCopy("smc_query_aggregate_test");
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @BeforeEach
    void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "viewer", "viewer@test", 1L, false, Set.of("test.form.view"), 1L, false, 1L, null));
    }

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    private static QueryAggregateResult run(
            QueryAggregateRepository repo, String groupBy, String measures, String filter) {
        QueryAggregate aggregate = QueryAggregates.compile(LIST, groupBy, measures, filter);
        return tx.execute(status -> repo.run(aggregate, new QueryPlan.SqlFragment("", Map.of())));
    }

    @Test
    @DisplayName("5.8: groups come back as codes, yes/no and keys, measures as counts and numbers, in group order")
    void valuesComeBackTyped() {
        QueryAggregateResult result = run(
                new QueryAggregateRepository(jdbc),
                "[{\"field\":\"kind\"},{\"field\":\"third\"}]",
                "[{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"amount\"},{\"op\":\"min\",\"field\":\"amount\"}]",
                "[{\"field\":\"g\",\"op\":\"lte\",\"value\":6}]");

        assertThat(result.truncated()).isFalse();
        assertThat(result.groups())
                .containsExactly(
                        new QueryAggregateResult.AggregateGroup("kind", null, false),
                        new QueryAggregateResult.AggregateGroup("third", null, false));
        assertThat(result.measures())
                .containsExactly(
                        new QueryAggregateResult.AggregateMeasure("count", null),
                        new QueryAggregateResult.AggregateMeasure("sum", "amount"),
                        new QueryAggregateResult.AggregateMeasure("min", "amount"));
        assertThat(result.rows())
                .extracting(QueryAggregateResult.AggregateRow::groups)
                .containsExactly(
                        List.of("even", false), List.of("even", true), List.of("odd", false), List.of("odd", true));
        assertThat(result.rows().getFirst().values()).containsExactly(2L, new BigDecimal("9.0"), new BigDecimal("3.0"));

        QueryAggregateResult byRef = run(new QueryAggregateRepository(jdbc), "[{\"field\":\"refId\"}]", null, null);
        assertThat(byRef.rows()).extracting(row -> row.groups().getFirst()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(byRef.rows()).extracting(row -> row.values().getFirst()).containsExactly(275L, 275L, 275L, 275L);
    }

    @Test
    @DisplayName("5.8: a moment is bucketed in UTC, a date by its own calendar; the first day names the bucket")
    void bucketsAreNamedByTheirFirstDay() {
        QueryAggregateResult months = run(
                new QueryAggregateRepository(jdbc),
                "[{\"field\":\"at\",\"trunc\":\"year\"}]",
                null,
                "[{\"field\":\"g\",\"op\":\"between\",\"value\":[1,400]}]");
        assertThat(months.rows())
                .extracting(QueryAggregateResult.AggregateRow::groups)
                .containsExactly(Arrays.asList("2024-01-01"), Arrays.asList("2025-01-01"));
        QueryAggregateResult quarters = run(
                new QueryAggregateRepository(jdbc),
                "[{\"field\":\"day\",\"trunc\":\"quarter\"}]",
                null,
                "[{\"field\":\"g\",\"op\":\"lte\",\"value\":100}]");
        assertThat(quarters.rows())
                .extracting(row -> row.groups().getFirst())
                .containsExactly("2024-01-01", "2024-04-01");
    }

    @Test
    @DisplayName("5.8: more than 1000 groups answer the first 1000 and say that the answer was cut")
    void manyGroupsAreCut() {
        QueryAggregateResult days =
                run(new QueryAggregateRepository(jdbc), "[{\"field\":\"day\",\"trunc\":\"day\"}]", null, null);

        assertThat(days.truncated()).isTrue();
        assertThat(days.rows()).hasSize(QueryAggregate.MAX_ROWS);
        assertThat(days.rows().getFirst().groups()).containsExactly("2024-01-02");
    }

    @Test
    @DisplayName("5.8 (ADR-0032, 12): a report longer than its limit is refused with 422, not left running")
    void slowReportIsRefused() {
        QueryList slow = new QueryList(
                "test.slow",
                "test.form",
                "view",
                "t.g",
                "(select g from generate_series(1, 3) g where pg_sleep(1) is not null) t",
                "t.g",
                List.of(QueryField.of("g", "g", QueryFieldType.NUMBER, "t.g").asSortable()),
                "g");
        QueryAggregateRepository repo = QueryAggregateRepository.limitedTo(jdbc, Duration.ofMillis(200));
        QueryAggregate aggregate = QueryAggregates.compile(slow, (String) null, null, null);

        ApiException refused = catchThrowableOfType(
                ApiException.class,
                () -> tx.executeWithoutResult(status -> repo.run(aggregate, new QueryPlan.SqlFragment("", Map.of()))));
        assertThat(refused).isNotNull();
        assertThat(refused.getMessageKey()).isEqualTo("error.common.report_too_slow");
        assertThat(refused.getErrorCode().getDefaultStatus()).isEqualTo(422);
    }
}
