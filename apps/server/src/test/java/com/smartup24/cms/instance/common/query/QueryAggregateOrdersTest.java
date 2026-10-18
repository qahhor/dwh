package com.smartup24.cms.instance.common.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The order of a report's groups and the top it keeps (plan 10/10, the form and report builder): a measure orders the
 * groups in the SQL itself, by its column only, and the query reads one group beyond the top; an order by words reads
 * every group and is ordered once the words are known; each wrong part is refused where it stands.
 */
class QueryAggregateOrdersTest {
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
                    QueryField.of("qty", "q", QueryFieldType.NUMBER, "o.qty")),
            "number");
    private static final String BY_STATUS = "[{\"field\":\"status\"}]";
    private static final String MEASURES = "[{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"qty\"}]";

    @BeforeEach
    void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "viewer", "viewer@test", 1L, false, Set.of("test.form.view"), 1L, false, 1L, null));
    }

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    @Test
    void aMeasureOrdersTheGroupsInTheQueryAndTheTopCutsIt() {
        QueryAggregate aggregate = QueryAggregates.compile(
                LIST, BY_STATUS, MEASURES, null, "{\"by\":\"measure\",\"measure\":1,\"direction\":\"desc\"}", 3);
        assertThat(aggregate.order()).isEqualTo(new QueryAggregate.Order(QueryAggregate.By.MEASURE, 1, true, 3));
        var sql = aggregate.sql(new QueryPlan.SqlFragment("", Map.of()));
        assertThat(sql.sql()).contains("order by q_m1 desc nulls last, 1 nulls last limit :q_rows");
        assertThat(sql.params()).containsEntry("q_rows", 4);
    }

    @Test
    void anOrderByWordsReadsEveryGroupAndTheValuesAscendByDefault() {
        QueryAggregate words = QueryAggregates.compile(LIST, BY_STATUS, MEASURES, null, "{\"by\":\"label\"}", 2);
        assertThat(words.order().cap()).isEqualTo(QueryAggregate.MAX_ROWS);
        assertThat(words.sql(new QueryPlan.SqlFragment("", Map.of())).params())
                .containsEntry("q_rows", QueryAggregate.MAX_ROWS + 1);
        QueryAggregate top = QueryAggregates.compile(LIST, BY_STATUS, MEASURES, null, null, 5);
        assertThat(top.order()).isEqualTo(new QueryAggregate.Order(QueryAggregate.By.GROUP, 0, false, 5));
        assertThat(QueryAggregates.compile(LIST, BY_STATUS, MEASURES, null).order())
                .isEqualTo(QueryAggregate.Order.DEFAULT);
    }

    @Test
    void eachWrongPartIsRefusedWhereItStands() {
        assertThat(problems("{\"by\":\"size\"}", null)).containsExactly("sort.by");
        assertThat(problems("{\"by\":\"label\",\"direction\":\"up\"}", null)).containsExactly("sort.direction");
        assertThat(problems("{\"by\":\"label\",\"measure\":0}", null)).containsExactly("sort.measure");
        assertThat(problems("{\"by\":\"measure\"}", null)).containsExactly("sort.measure");
        assertThat(problems("{\"by\":\"measure\",\"measure\":2}", null)).containsExactly("sort.measure");
        assertThat(problems("{\"by\":\"label\",\"top\":1}", null)).containsExactly("sort.top");
        assertThat(problems("[1]", null)).containsExactly("sort");
        assertThat(problems("{oops", null)).containsExactly("sort");
        assertThat(problems(null, 0)).containsExactly("limit");
        assertThat(problems(null, QueryAggregate.MAX_ROWS + 1)).containsExactly("limit");
        ApiException ungrouped = catchThrowableOfType(
                ApiException.class,
                () -> QueryAggregates.compile(LIST, null, MEASURES, null, "{\"by\":\"label\"}", null));
        assertThat(ungrouped.getFieldErrors())
                .extracting(FieldErrorItem::messageKey)
                .containsExactly("error.common.field_report_order_ungrouped");
        assertThatThrownBy(() -> new QueryAggregate.Order(QueryAggregate.By.GROUP, 0, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> problems(String sort, Integer limit) {
        ApiException refused = catchThrowableOfType(
                ApiException.class, () -> QueryAggregates.compile(LIST, BY_STATUS, MEASURES, null, sort, limit));
        assertThat(refused).as("refused").isNotNull();
        return refused.getFieldErrors().stream().map(FieldErrorItem::field).toList();
    }
}
