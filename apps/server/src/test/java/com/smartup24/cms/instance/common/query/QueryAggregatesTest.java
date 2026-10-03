package com.smartup24.cms.instance.common.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.8 (ADR-0032, 10.2): a report is validated against the registry, as a page is, and its SQL is made
 * of the registry's expressions only — a key, a bucket or an operation a client sends never becomes SQL text, a filter
 * value is always a parameter, and a field the viewer may not see is no field of the report.
 */
class QueryAggregatesTest {

    private static final QueryList LIST = new QueryList(
            "test.orders",
            "test.form",
            "view",
            "o.id",
            "test_orders o",
            "o.id",
            List.of(
                    QueryField.of("number", "n", QueryFieldType.TEXT, "o.number")
                            .asSortable(),
                    QueryField.enumeration("status", "s", "o.status", List.of("draft", "posted"), "test.status."),
                    QueryField.of("paid", "p", QueryFieldType.BOOLEAN, "o.paid"),
                    QueryField.of("orderedOn", "d", QueryFieldType.DATE, "o.ordered_on"),
                    QueryField.of("createdAt", "c", QueryFieldType.INSTANT, "o.created_at"),
                    QueryField.of("customerId", "cu", QueryFieldType.NUMBER, "o.customer_id")
                            .refersTo(QueryRef.whole("/customers", "name")),
                    QueryField.of("qty", "q", QueryFieldType.NUMBER, "o.qty"),
                    QueryField.of("total", "t", QueryFieldType.NUMBER, "o.total_amount")
                            .formatted(QueryAggregates.MONEY_FORMAT),
                    new QueryField(
                            "totalCurrency",
                            "t",
                            QueryFieldType.ENUM,
                            "o.total_currency",
                            true,
                            false,
                            false,
                            false,
                            List.of("UZS", "USD"),
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null,
                            QueryAggregates.CURRENCY_FORMAT,
                            null),
                    QueryField.of("cost", "co", QueryFieldType.NUMBER, "o.cost").requires("test.money", "view"),
                    QueryField.enumeration("region", "r", "o.region", List.of("north"), null)
                            .requires("test.money", "view")),
            "number");

    @BeforeEach
    void signIn() {
        signIn("test.form.view");
    }

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    private static void signIn(String... permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "viewer", "viewer@test", 1L, false, Set.of(permissions), 1L, false, 1L, null));
    }

    private static QueryAggregate compile(String groupBy, String measures, String filter) {
        return QueryAggregates.compile(LIST, groupBy, measures, filter);
    }

    private static List<FieldErrorItem> errors(String groupBy, String measures, String filter) {
        ApiException e = catchThrowableOfType(ApiException.class, () -> compile(groupBy, measures, filter));
        assertThat(e).as("expected a validation error").isNotNull();
        assertThat(e.getMessageKey()).isEqualTo("error.common.report_invalid");
        return e.getFieldErrors();
    }

    @Test
    @DisplayName("5.8: no grouping and no measures — one row with the count of the scoped, filtered rows")
    void emptyReportCountsTheRows() {
        QueryAggregate aggregate = compile(null, null, null);

        assertThat(aggregate.groups()).isEmpty();
        assertThat(aggregate.measures())
                .extracting(QueryAggregate.Measure::op)
                .containsExactly(QueryAggregate.Op.COUNT);
        QueryPlan.SqlFragment sql =
                aggregate.sql(new QueryPlan.SqlFragment(" and o.owner_id = :scopeUserId", Map.of("scopeUserId", 7L)));
        assertThat(sql.sql())
                .isEqualTo("select count(*) as q_m0 from test_orders o where 1=1 and o.owner_id = :scopeUserId"
                        + " limit :q_rows");
        assertThat(sql.params()).containsEntry("scopeUserId", 7L).containsEntry("q_rows", 1001);
    }

    @Test
    @DisplayName("5.8: groups by a choice and a month, measures from the registry, ordered and limited")
    void groupsAndMeasuresBecomeRegistrySql() {
        QueryAggregate aggregate = compile(
                "[{\"field\":\"status\"},{\"field\":\"orderedOn\",\"trunc\":\"month\"}]",
                "[{\"op\":\"count\"},{\"op\":\"avg\",\"field\":\"qty\"},{\"op\":\"max\",\"field\":\"qty\"}]",
                "[{\"field\":\"paid\",\"op\":\"eq\",\"value\":true}]");

        QueryPlan.SqlFragment sql = aggregate.sql(new QueryPlan.SqlFragment("", Map.of()));
        assertThat(sql.sql())
                .isEqualTo("select o.status as q_g0,"
                        + " cast(date_trunc('month', cast(o.ordered_on as timestamp)) as date) as q_g1,"
                        + " count(*) as q_m0, round(avg(o.qty), 4) as q_m1, max(o.qty) as q_m2"
                        + " from test_orders o where 1=1 and o.paid = :q_f0"
                        + " group by 1, 2 order by 1 nulls last, 2 nulls last limit :q_rows");
        assertThat(sql.params()).containsEntry("q_f0", true);
    }

    @Test
    @DisplayName("5.8: a moment is bucketed in UTC; a date without a bucket is bucketed by month")
    void datesAreBucketed() {
        QueryAggregate aggregate =
                compile("[{\"field\":\"createdAt\",\"trunc\":\"week\"},{\"field\":\"orderedOn\"}]", null, null);

        assertThat(aggregate.groups())
                .extracting(QueryAggregate.Group::trunc)
                .containsExactly(QueryAggregate.Trunc.WEEK, QueryAggregate.Trunc.MONTH);
        assertThat(aggregate.sql(new QueryPlan.SqlFragment("", Map.of())).sql())
                .contains("cast(date_trunc('week', (o.created_at) at time zone 'UTC') as date) as q_g0");
    }

    @Test
    @DisplayName("5.8: a sum of money is grouped by its currency as well, once")
    void moneyIsGroupedByItsCurrency() {
        QueryAggregate aggregate = compile(
                "[{\"field\":\"status\"}]",
                "[{\"op\":\"sum\",\"field\":\"total\"},{\"op\":\"min\",\"field\":\"total\"}]",
                null);

        assertThat(aggregate.groups())
                .extracting(group -> group.field().key(), QueryAggregate.Group::implicit)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("status", false),
                        org.assertj.core.groups.Tuple.tuple("totalCurrency", true));
        assertThat(aggregate.sql(new QueryPlan.SqlFragment("", Map.of())).sql())
                .startsWith("select o.status as q_g0, o.total_currency as q_g1, sum(o.total_amount) as q_m0")
                .contains("group by 1, 2");
    }

    @Test
    @DisplayName("5.8: a reference groups by its row key; text, a plain number and a time do not group")
    void whatGroups() {
        assertThat(compile("[{\"field\":\"customerId\"},{\"field\":\"paid\"}]", null, null)
                        .groups())
                .hasSize(2);

        assertThat(errors("[{\"field\":\"number\"},{\"field\":\"qty\"}]", null, null))
                .extracting(FieldErrorItem::field, FieldErrorItem::messageKey)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "groupBy[0].field", "error.common.field_report_group_invalid"),
                        org.assertj.core.groups.Tuple.tuple(
                                "groupBy[1].field", "error.common.field_report_group_invalid"));
        assertThat(errors("[{\"field\":\"status\",\"trunc\":\"month\"}]", null, null))
                .extracting(FieldErrorItem::field)
                .containsExactly("groupBy[0].trunc");
        assertThat(errors("[{\"field\":\"orderedOn\",\"trunc\":\"decade\"}]", null, null))
                .extracting(FieldErrorItem::field)
                .containsExactly("groupBy[0].trunc");
        assertThat(errors("[{\"field\":\"status\"},{\"field\":\"status\"}]", null, null))
                .extracting(FieldErrorItem::messageKey)
                .containsExactly("error.common.field_report_repeated");
        assertThat(errors("[{\"field\":\"status\"},{\"field\":\"paid\"},{\"field\":\"orderedOn\"}]", null, null))
                .extracting(FieldErrorItem::messageKey)
                .containsExactly("error.common.field_report_groups_too_many");
    }

    @Test
    @DisplayName("5.8: only a number or money is measured; count takes no field; an operation is one of five")
    void whatMeasures() {
        assertThat(errors(
                        null,
                        "[{\"op\":\"sum\",\"field\":\"customerId\"},{\"op\":\"avg\",\"field\":\"status\"},"
                                + "{\"op\":\"count\",\"field\":\"qty\"},{\"op\":\"median\",\"field\":\"qty\"}]",
                        null))
                .extracting(FieldErrorItem::field, FieldErrorItem::messageKey)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "measures[0].field", "error.common.field_report_measure_invalid"),
                        org.assertj.core.groups.Tuple.tuple(
                                "measures[1].field", "error.common.field_report_measure_invalid"),
                        org.assertj.core.groups.Tuple.tuple(
                                "measures[2].field", "error.common.field_report_count_field"),
                        org.assertj.core.groups.Tuple.tuple("measures[3].op", "error.common.field_report_op_invalid"));
        assertThat(errors(null, "[{\"op\":\"sum\"}]", null))
                .extracting(FieldErrorItem::field, FieldErrorItem::messageKey)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "measures[0].field", "error.common.field_report_field_unknown"));
        assertThat(errors(null, "[{\"op\":\"count\"},{\"op\":\"count\"}]", null))
                .extracting(FieldErrorItem::messageKey)
                .containsExactly("error.common.field_report_repeated");
        assertThat(errors(
                        null,
                        "[{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"qty\"},{\"op\":\"min\",\"field\":\"qty\"},"
                                + "{\"op\":\"max\",\"field\":\"qty\"},{\"op\":\"avg\",\"field\":\"qty\"}]",
                        null))
                .extracting(FieldErrorItem::messageKey)
                .containsExactly("error.common.field_report_measures_too_many");
    }

    @Test
    @DisplayName("5.8: a restricted field answers as an unknown one in grouping and measures until the right is held")
    void restrictedFieldsAreNoFieldsOfTheReport() {
        assertThat(errors("[{\"field\":\"region\"}]", "[{\"op\":\"sum\",\"field\":\"cost\"}]", null))
                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("groupBy[0].field", QueryCompiler.UNKNOWN_FIELD),
                        org.assertj.core.groups.Tuple.tuple("measures[0].field", QueryCompiler.UNKNOWN_FIELD));
        assertThat(errors(null, null, "[{\"field\":\"cost\",\"op\":\"gt\",\"value\":1}]"))
                .extracting(FieldErrorItem::field)
                .containsExactly("filter[0].field");

        signIn("test.form.view", "test.money.view");
        assertThat(compile("[{\"field\":\"region\"}]", "[{\"op\":\"sum\",\"field\":\"cost\"}]", null)
                        .sql(new QueryPlan.SqlFragment("", Map.of()))
                        .sql())
                .contains("o.region as q_g0", "sum(o.cost) as q_m0");
    }

    @Test
    @DisplayName("5.8 (ADR-0032, 12): hostile keys, buckets and values never reach the SQL text")
    void hostileInputNeverBecomesSql() {
        String evil = "status; drop table test_orders; --";
        List<FieldErrorItem> problems = errors(
                "[{\"field\":\"" + evil + "\"},{\"field\":\"orderedOn\",\"trunc\":\"month') as date; --\"}]",
                "[{\"op\":\"sum(o.secret)--\"},{\"op\":\"sum\",\"field\":\"o.qty\"}]",
                null);
        assertThat(problems)
                .extracting(FieldErrorItem::field)
                .containsExactly("groupBy[0].field", "groupBy[1].trunc", "measures[0].op", "measures[1].field");
        assertThat(errors("[{\"field\":\"status\",\"sql\":\"1\"}]", null, null))
                .extracting(FieldErrorItem::field)
                .containsExactly("groupBy[0].sql");
        assertThat(errors("not json", null, null))
                .extracting(FieldErrorItem::field)
                .containsExactly("groupBy");
        assertThat(errors("{\"field\":\"status\"}", null, null))
                .extracting(FieldErrorItem::field)
                .containsExactly("groupBy");

        QueryPlan.SqlFragment sql = compile(
                        "[{\"field\":\"status\"}]",
                        null,
                        "[{\"field\":\"status\",\"op\":\"in\",\"value\":[\"draft\"]},"
                                + "{\"field\":\"number\",\"op\":\"contains\",\"value\":\"x' or '1'='1\"}]")
                .sql(new QueryPlan.SqlFragment("", Map.of()));
        assertThat(sql.sql()).doesNotContain("'1'='1").doesNotContain("draft");
        assertThat(sql.params()).containsEntry("q_f1", "%x' or '1'='1%");
    }

    @Test
    @DisplayName("5.8: a plan without a measure or with a bucket on a choice cannot be built by hand")
    void thePlanGuardsItsShape() {
        QueryField status = LIST.field("status").orElseThrow();
        assertThatThrownBy(() -> new QueryAggregate.Group(status, QueryAggregate.Trunc.DAY, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryAggregate.Measure(QueryAggregate.Op.SUM, null))
                .isInstanceOf(IllegalArgumentException.class);
        QueryPlan plan = QueryCompiler.compile(LIST, null, null, null, null);
        assertThatThrownBy(() -> new QueryAggregate(LIST, List.of(), List.of(), plan))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
