package com.smartup24.cms.instance.common.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateMeasure;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateRow;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateTotal;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0044: the analytics of a report run as one statement on PostgreSQL — the different values counted exactly (a total
 * of distinct clients is not the sum of its groups), the totals and shares of a flat report and of a pivot, the bounds of
 * a pivot refused rather than cut, and two periods compared in the reader's time zone with the change and its percent.
 *
 * <p>Twelve records: {@code g} 1…12, region {@code south}/{@code east}/{@code north} by {@code g % 3}, a day every 15
 * days from 2026-01-01, an amount of {@code g * 10} and a client {@code (g + 1) / 2}.
 */
class QueryAggregateAnalyticsTest {

    private static final String ROWS = "(select g, case g % 3 when 0 then 'north' when 1 then 'south' else 'east' end"
            + " as region, (g + 1) / 2 as client, g * 10 as amount, date '2026-01-01' + (g - 1) * 15 as day,"
            + " timestamptz '2026-01-01 00:30:00+05' + (g - 1) * interval '15 days' as at"
            + " from generate_series(1, 12) g) t";

    private static final QueryList LIST = new QueryList(
            "test.orders",
            "test.form",
            "view",
            "t.g",
            ROWS,
            "t.g",
            List.of(
                    QueryField.of("g", "g", QueryFieldType.NUMBER, "t.g").asSortable(),
                    QueryField.enumeration("region", "r", "t.region", List.of("north", "south", "east"), null),
                    QueryField.of("client", "c", QueryFieldType.NUMBER, "t.client")
                            .refersTo(QueryRef.whole("/clients", "name")),
                    QueryField.of("amount", "a", QueryFieldType.NUMBER, "t.amount"),
                    QueryField.of("day", "d", QueryFieldType.DATE, "t.day"),
                    QueryField.of("at", "i", QueryFieldType.INSTANT, "t.at")),
            "g");

    private static final QueryAggregateSpec.Anchor MARCH_20 =
            new QueryAggregateSpec.Anchor(ZoneId.of("Asia/Tashkent"), LocalDate.of(2026, 3, 20));
    private static final JsonMapper JSON = JsonMapper.shared();

    static JdbcClient jdbc;
    static TransactionTemplate tx;

    @BeforeAll
    static void setup() {
        DataSource ds = TestDatabases.migratedCopy("smc_query_analytics_test");
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

    private static QueryAggregateResult run(QueryList list, String spec) {
        JsonNode node = JSON.readTree(spec);
        QueryAggregate aggregate = QueryAggregates.compile(list, QueryAggregateSpec.ofState(node, null), MARCH_20);
        QueryAggregateResult raw = tx.execute(
                status -> new QueryAggregateRepository(jdbc).run(aggregate, new QueryPlan.SqlFragment("", Map.of())));
        return QueryAggregateFinish.finish(aggregate, raw);
    }

    private static List<FieldErrorItem> refused(String spec) {
        ApiException failure = catchThrowableOfType(ApiException.class, () -> run(LIST, spec));
        assertThat(failure).isNotNull();
        return failure.getFieldErrors();
    }

    private static List<Object> values(AggregateRow row) {
        return row.values();
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("ADR-0044: count distinct is exact per group and in the total, which is not the sum of the groups")
    void countDistinctIsExact() {
        QueryAggregateResult result = run(
                LIST,
                "{\"groupBy\":[{\"field\":\"region\"}],\"measures\":[{\"op\":\"count_distinct\",\"field\":\"client\"},"
                        + "{\"op\":\"count\"}],\"totals\":true}");

        assertThat(result.measures())
                .containsExactly(new AggregateMeasure("count_distinct", "client"), new AggregateMeasure("count", null));
        assertThat(result.rows())
                .extracting(AggregateRow::groups)
                .containsExactly(List.of("east"), List.of("north"), List.of("south"));
        assertThat(result.rows())
                .extracting(QueryAggregateAnalyticsTest::values)
                .containsExactly(List.of(4L, 4L), List.of(4L, 4L), List.of(4L, 4L));
        assertThat(result.totals()).singleElement().satisfies(total -> {
            assertThat(total.kind()).isEqualTo("grand");
            assertThat(total.groups()).containsExactly((Object) null);
            assertThat(total.values()).containsExactly(6L, 12L);
        });
    }

    @Test
    @DisplayName("ADR-0044: a share of the total is the group's sum in percent of the exact total, even past a top")
    void sharesOfTheTotal() {
        QueryAggregateResult result = run(
                LIST,
                "{\"groupBy\":[{\"field\":\"region\"}],\"measures\":[{\"op\":\"sum\",\"field\":\"amount\"},"
                        + "{\"op\":\"sum\",\"field\":\"amount\",\"share\":\"total\"}],"
                        + "\"sort\":{\"by\":\"measure\",\"measure\":0,\"direction\":\"desc\"},\"limit\":2}");

        assertThat(result.measures().get(1)).isEqualTo(new AggregateMeasure("sum", "amount", "total", null));
        assertThat(result.truncated()).isTrue();
        assertThat(result.rows())
                .extracting(QueryAggregateAnalyticsTest::values)
                .containsExactly(List.of(dec("300"), dec("38.46")), List.of(dec("260"), dec("33.33")));
        assertThat(result.totals())
                .singleElement()
                .extracting(AggregateTotal::values)
                .isEqualTo(List.of(dec("780"), dec("100.00")));
    }

    @Test
    @DisplayName("ADR-0044: a pivot has cells, totals of rows, columns and the whole, and shares of each")
    void pivotWithTotalsAndShares() {
        QueryAggregateResult result = run(
                LIST,
                "{\"groupBy\":[{\"field\":\"region\"},{\"field\":\"day\",\"trunc\":\"quarter\"}],"
                        + "\"measures\":[{\"op\":\"sum\",\"field\":\"amount\"},"
                        + "{\"op\":\"sum\",\"field\":\"amount\",\"share\":\"row\"},"
                        + "{\"op\":\"sum\",\"field\":\"amount\",\"share\":\"column\"},"
                        + "{\"op\":\"count\",\"share\":\"total\"}],\"pivot\":true}");

        assertThat(result.pivot()).isTrue();
        assertThat(result.truncated()).isFalse();
        // Q1 holds g 1..6, Q2 holds g 7..12: north 3,6 | 9,12; south 1,4 | 7,10; east 2,5 | 8,11.
        assertThat(result.rows())
                .extracting(AggregateRow::groups)
                .containsExactly(
                        List.of("east", "2026-01-01"), List.of("east", "2026-04-01"),
                        List.of("north", "2026-01-01"), List.of("north", "2026-04-01"),
                        List.of("south", "2026-01-01"), List.of("south", "2026-04-01"));
        assertThat(result.rows().get(2).values()).containsExactly(dec("90"), dec("30.00"), dec("42.86"), dec("16.67"));
        List<AggregateTotal> rows =
                result.totals().stream().filter(t -> t.kind().equals("row")).toList();
        List<AggregateTotal> columns =
                result.totals().stream().filter(t -> t.kind().equals("column")).toList();
        List<AggregateTotal> grand =
                result.totals().stream().filter(t -> t.kind().equals("grand")).toList();
        assertThat(rows).extracting(t -> t.groups().getFirst()).containsExactly("east", "north", "south");
        assertThat(rows.get(1).values()).containsExactly(dec("300"), dec("100.00"), dec("38.46"), dec("33.33"));
        assertThat(columns).extracting(t -> t.groups().get(1)).containsExactly("2026-01-01", "2026-04-01");
        assertThat(columns.getFirst().values()).containsExactly(dec("210"), dec("26.92"), dec("100.00"), dec("50.00"));
        assertThat(grand)
                .singleElement()
                .extracting(AggregateTotal::values)
                .isEqualTo(List.of(dec("780"), dec("100.00"), dec("100.00"), dec("100.00")));
    }

    @Test
    @DisplayName("ADR-0044: a pivot's rows follow a measure and its top; the columns' totals still cover every row")
    void pivotTopRows() {
        QueryAggregateResult result = run(
                LIST,
                "{\"groupBy\":[{\"field\":\"region\"},{\"field\":\"day\",\"trunc\":\"quarter\"}],"
                        + "\"measures\":[{\"op\":\"sum\",\"field\":\"amount\"}],\"pivot\":true,"
                        + "\"sort\":{\"by\":\"measure\",\"measure\":0,\"direction\":\"desc\"},\"limit\":2}");

        assertThat(result.truncated()).isTrue();
        assertThat(result.rows())
                .extracting(row -> row.groups().getFirst())
                .containsExactly("north", "north", "east", "east");
        assertThat(result.totals().stream().filter(t -> t.kind().equals("column")))
                .extracting(t -> t.values().getFirst())
                .containsExactly(dec("210"), dec("570"));
    }

    @Test
    @DisplayName("ADR-0044: a pivot of more than 50 columns is refused with a request to narrow it")
    void widePivotIsRefused() {
        ApiException failure = catchThrowableOfType(
                ApiException.class,
                () -> run(
                        LIST.withFields(LIST.fields()),
                        "{\"groupBy\":[{\"field\":\"region\"},{\"field\":\"g\"}],\"pivot\":true}"));
        assertThat(failure).isNotNull();
        assertThat(failure.getMessageKey()).isEqualTo("error.common.report_invalid");

        QueryList wide = new QueryList(
                "test.wide",
                "test.form",
                "view",
                "t.g",
                "(select g, case when g % 2 = 0 then 'even' else 'odd' end as kind, date '2026-01-01' + g as day"
                        + " from generate_series(1, 120) g) t",
                "t.g",
                List.of(
                        QueryField.of("g", "g", QueryFieldType.NUMBER, "t.g").asSortable(),
                        QueryField.enumeration("kind", "k", "t.kind", List.of("even", "odd"), null),
                        QueryField.of("day", "d", QueryFieldType.DATE, "t.day")),
                "g");
        ApiException wideFailure = catchThrowableOfType(
                ApiException.class,
                () -> run(
                        wide,
                        "{\"groupBy\":[{\"field\":\"kind\"},{\"field\":\"day\",\"trunc\":\"day\"}],"
                                + "\"pivot\":true}"));
        assertThat(wideFailure).isNotNull();
        assertThat(wideFailure.getMessageKey()).isEqualTo("error.common.report_pivot_columns");
        assertThat(wideFailure.getErrorCode().getDefaultStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("ADR-0044: this month against the last: the figures, the change and its percent, empty over zero")
    void monthAgainstPrevious() {
        QueryAggregateResult result = run(
                LIST,
                "{\"measures\":[{\"op\":\"sum\",\"field\":\"amount\"},{\"op\":\"count\"}],"
                        + "\"compare\":{\"field\":\"day\",\"period\":\"month\"}}");

        assertThat(result.comparison()).isNotNull();
        assertThat(result.comparison().currentFrom()).isEqualTo("2026-03-01");
        assertThat(result.comparison().currentTo()).isEqualTo("2026-03-31");
        assertThat(result.comparison().previousFrom()).isEqualTo("2026-02-01");
        assertThat(result.comparison().previousTo()).isEqualTo("2026-02-28");
        assertThat(result.measures())
                .extracting(AggregateMeasure::period)
                .containsExactly(
                        "current",
                        "previous",
                        "change",
                        "change_percent",
                        "current",
                        "previous",
                        "change",
                        "change_percent");
        // March: g 5 (Mar 2) and g 6 (Mar 17); February: g 4 (Feb 15).
        assertThat(result.rows())
                .singleElement()
                .extracting(AggregateRow::values)
                .isEqualTo(List.of(dec("110"), dec("40"), dec("70"), dec("175.00"), 2L, 1L, 1L, dec("100.00")));

        QueryAggregateResult byRegion = run(
                LIST,
                "{\"groupBy\":[{\"field\":\"region\"}],\"measures\":[{\"op\":\"sum\",\"field\":\"amount\"}],"
                        + "\"compare\":{\"field\":\"day\",\"period\":\"month\",\"against\":\"previous\"},"
                        + "\"totals\":true}");
        assertThat(byRegion.rows())
                .extracting(AggregateRow::groups)
                .containsExactly(List.of("east"), List.of("north"), List.of("south"));
        assertThat(byRegion.rows().get(1).values()).containsExactly(dec("60"), null, null, null);
        assertThat(byRegion.rows().get(2).values()).containsExactly(null, dec("40"), null, null);
        assertThat(byRegion.totals())
                .singleElement()
                .extracting(AggregateTotal::values)
                .isEqualTo(List.of(dec("110"), dec("40"), dec("70"), dec("175.00")));
    }

    @Test
    @DisplayName("ADR-0044: a year earlier, the year to date and a custom range; a moment counts in the reader's zone")
    void otherPeriods() {
        QueryAggregateResult year = run(
                LIST,
                "{\"measures\":[{\"op\":\"count\"}],\"compare\":{\"field\":\"day\",\"period\":\"quarter\","
                        + "\"against\":\"year\"}}");
        assertThat(year.comparison().previousFrom()).isEqualTo("2025-01-01");
        assertThat(year.comparison().previousTo()).isEqualTo("2025-03-31");
        assertThat(year.rows().getFirst().values()).containsExactly(6L, 0L, 6L, null);

        QueryAggregateResult ytd =
                run(LIST, "{\"measures\":[{\"op\":\"count\"}],\"compare\":{\"field\":\"day\",\"period\":\"ytd\"}}");
        assertThat(ytd.comparison().currentTo()).isEqualTo("2026-03-20");
        assertThat(ytd.comparison().previousTo()).isEqualTo("2025-03-20");
        assertThat(ytd.rows().getFirst().values().getFirst()).isEqualTo(6L);

        QueryAggregateResult custom = run(
                LIST,
                "{\"measures\":[{\"op\":\"count\"}],\"compare\":{\"field\":\"day\",\"period\":\"custom\","
                        + "\"from\":\"2026-01-16\",\"to\":\"2026-01-31\"}}");
        assertThat(custom.comparison().previousFrom()).isEqualTo("2025-12-31");
        assertThat(custom.comparison().previousTo()).isEqualTo("2026-01-15");
        assertThat(custom.rows().getFirst().values()).containsExactly(2L, 1L, 1L, dec("100.00"));

        // The moments are 00:30 in Tashkent, 19:30 UTC the day before: still the same Tashkent day as the dates.
        QueryAggregateResult moments = run(
                LIST,
                "{\"measures\":[{\"op\":\"count\"}],\"compare\":{\"field\":\"at\",\"period\":\"custom\","
                        + "\"from\":\"2026-01-16\",\"to\":\"2026-01-31\"}}");
        assertThat(moments.rows().getFirst().values()).containsExactly(2L, 1L, 1L, dec("100.00"));
    }

    @Test
    @DisplayName("ADR-0044: a share, a pivot and a comparison that do not fit are refused at the part to fix")
    void misfitsAreRefused() {
        assertThat(refused("{\"measures\":[{\"op\":\"avg\",\"field\":\"amount\",\"share\":\"total\"}]}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("measures[0].share");
        assertThat(refused(
                        "{\"groupBy\":[{\"field\":\"region\"}],\"measures\":[{\"op\":\"count\",\"share\":\"row\"}]}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("measures[0].share");
        assertThat(refused("{\"groupBy\":[{\"field\":\"region\"}],\"pivot\":true}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("pivot");
        assertThat(refused("{\"groupBy\":[{\"field\":\"region\"}],\"pivot\":\"yes\"}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("pivot");
        assertThat(refused("{\"groupBy\":[{\"field\":\"day\"}],\"compare\":{\"field\":\"day\",\"period\":\"month\"}}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("compare.field");
        assertThat(refused("{\"compare\":{\"field\":\"amount\",\"period\":\"month\"}}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("compare.field");
        assertThat(refused("{\"compare\":{\"field\":\"day\",\"period\":\"month\",\"from\":\"2026-01-01\"}}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("compare.from");
        assertThat(refused("{\"compare\":{\"field\":\"day\",\"period\":\"custom\",\"from\":\"2026-02-01\","
                        + "\"to\":\"2026-01-01\"}}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("compare.to");
        assertThat(refused("{\"compare\":{\"field\":\"day\",\"period\":\"week\",\"x\":1}}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("compare.x");
        assertThat(refused("{\"measures\":[{\"op\":\"count\",\"share\":\"total\"}],"
                        + "\"groupBy\":[{\"field\":\"region\"}],\"compare\":{\"field\":\"day\",\"period\":\"month\"}}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("measures[0].share");
        assertThat(refused("{\"groupBy\":[{\"field\":\"region\"},{\"field\":\"day\"}],\"pivot\":true,"
                        + "\"compare\":{\"field\":\"at\",\"period\":\"month\"}}"))
                .extracting(FieldErrorItem::field)
                .containsExactly("compare");
        assertThat(Arrays.asList(QueryAggregateShapes.days(
                        QueryAggregateShape.Period.MONTH,
                        QueryAggregateShape.Against.YEAR,
                        LocalDate.of(2024, 2, 10),
                        null,
                        null)))
                .containsExactly(
                        LocalDate.of(2024, 2, 1),
                        LocalDate.of(2024, 2, 29),
                        LocalDate.of(2023, 2, 1),
                        LocalDate.of(2023, 2, 28));
    }

    @Test
    @DisplayName("ADR-0044: a measure computed by a formula over aggregates, per period and per total; empty over zero")
    void computedMeasures() {
        List<QueryComputedMeasure> computed = List.of(
                new QueryComputedMeasure("avgOrder", Map.of("ru", "Средний"), "sum_amount / count"),
                new QueryComputedMeasure("broken", Map.of("ru", "Ноль"), "sum_amount / (count - count)"),
                new QueryComputedMeasure("clients", Map.of("ru", "Клиенты"), "distinct_client"));
        JsonNode node = JSON.readTree("{\"measures\":[{\"op\":\"formula\",\"field\":\"avgOrder\"},"
                + "{\"op\":\"formula\",\"field\":\"broken\"}],"
                + "\"compare\":{\"field\":\"day\",\"period\":\"month\"}}");
        QueryAggregate aggregate = QueryAggregates.compile(
                LIST, QueryAggregateSpec.ofState(node, null).withComputed(computed), MARCH_20);
        QueryAggregateResult result = tx.execute(
                status -> new QueryAggregateRepository(jdbc).run(aggregate, new QueryPlan.SqlFragment("", Map.of())));
        assertThat(result.measures().getFirst())
                .isEqualTo(new AggregateMeasure("formula", "avgOrder", null, "current"));
        assertThat(result.rows().getFirst().values())
                .containsExactly(dec("55"), dec("40"), dec("15"), dec("37.50"), null, null, null, null);

        JsonNode grouped = JSON.readTree("{\"groupBy\":[{\"field\":\"region\"}],\"totals\":true,"
                + "\"measures\":[{\"op\":\"formula\",\"field\":\"clients\"}]}");
        QueryAggregate byRegion = QueryAggregates.compile(
                LIST, QueryAggregateSpec.ofState(grouped, null).withComputed(computed), MARCH_20);
        QueryAggregateResult totals = tx.execute(
                status -> new QueryAggregateRepository(jdbc).run(byRegion, new QueryPlan.SqlFragment("", Map.of())));
        assertThat(totals.totals().getFirst().values()).containsExactly(dec("6"));

        ApiException unknown = catchThrowableOfType(
                ApiException.class,
                () -> QueryAggregates.compile(
                        LIST,
                        QueryAggregateSpec.ofState(
                                        JSON.readTree("{\"measures\":[{\"op\":\"formula\",\"field\":\"none\"}]}"), null)
                                .withComputed(computed),
                        MARCH_20));
        assertThat(unknown.getFieldErrors()).extracting(FieldErrorItem::field).containsExactly("measures[0].field");
    }
}
