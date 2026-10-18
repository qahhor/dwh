package com.smartup24.cms.instance.report.mailing;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateGroup;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateMeasure;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateRow;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateTotal;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0045: the condition of a mailing judged on a recipient's result. */
class MailingConditionTest {
    private static final List<AggregateMeasure> SUM = List.of(new AggregateMeasure("sum", "total"));

    private static QueryAggregateResult grouped(List<AggregateRow> rows, List<AggregateTotal> totals) {
        return new QueryAggregateResult(
                List.of(new AggregateGroup("region", null, false)), SUM, rows, false, totals, false, null);
    }

    private static AggregateRow row(String group, Object value) {
        return new AggregateRow(List.of(group), List.of(value));
    }

    private static AggregateTotal grand(Object value) {
        return new AggregateTotal("grand", Arrays.asList((Object) null), List.of(value), Arrays.asList((String) null));
    }

    private static QueryAggregateResult flat(String op, Object value) {
        return new QueryAggregateResult(
                List.of(),
                List.of(new AggregateMeasure(op, "count".equals(op) ? null : "total")),
                List.of(new AggregateRow(List.of(), List.of(value))),
                false);
    }

    @Test
    @DisplayName("ADR-0045: has_rows needs a group, or a figure that is not empty or zero without groups")
    void hasRows() {
        var condition = new MailingCondition("has_rows", null, null, null);
        assertThat(condition.holds(grouped(List.of(row("north", 5)), List.of())))
                .isTrue();
        assertThat(condition.holds(grouped(List.of(), List.of()))).isFalse();
        assertThat(condition.holds(flat("count", 0))).isFalse();
        assertThat(condition.holds(flat("count", 3))).isTrue();
        assertThat(MailingCondition.always().holds(grouped(List.of(), List.of())))
                .isTrue();
    }

    @Test
    @DisplayName("ADR-0045: a threshold compares the grand total, the only row of a report without groups")
    void threshold() {
        var over = new MailingCondition("threshold", 0, "gt", new BigDecimal("100"));
        assertThat(over.holds(grouped(List.of(row("north", 80), row("south", 40)), List.of(grand(120)))))
                .isTrue();
        assertThat(over.holds(grouped(List.of(row("north", 80)), List.of(grand(80)))))
                .isFalse();
        assertThat(over.holds(flat("sum", 150))).isTrue();
        assertThat(new MailingCondition("threshold", 0, "lte", new BigDecimal("150")).holds(flat("sum", 150)))
                .isTrue();
        assertThat(new MailingCondition("threshold", 0, "lt", new BigDecimal("150")).holds(flat("sum", 150)))
                .isFalse();
        assertThat(new MailingCondition("threshold", 0, "gte", new BigDecimal("150")).holds(flat("sum", 150)))
                .isTrue();
        assertThat(new MailingCondition("threshold", 0, "eq", new BigDecimal("150.00")).holds(flat("sum", 150)))
                .isTrue();
        assertThat(over.holds(grouped(List.of(row("north", 500)), List.of())))
                .as("a grouped report without totals has no grand total")
                .isFalse();
    }

    @Test
    @DisplayName("ADR-0045: in a comparison the measure's column is its current period")
    void comparisonColumn() {
        List<AggregateMeasure> compared = List.of(
                new AggregateMeasure("sum", "total", null, "current"),
                new AggregateMeasure("sum", "total", null, "previous"),
                new AggregateMeasure("sum", "total", null, "change"),
                new AggregateMeasure("sum", "total", null, "change_percent"),
                new AggregateMeasure("count", null, null, "current"));
        assertThat(MailingCondition.column(compared, 0)).isZero();
        assertThat(MailingCondition.column(compared, 1)).isEqualTo(4);
        assertThat(MailingCondition.column(compared, 2)).isEqualTo(-1);
    }
}
