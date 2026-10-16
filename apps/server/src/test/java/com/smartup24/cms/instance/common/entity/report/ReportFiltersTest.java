package com.smartup24.cms.instance.common.entity.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregates;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReportFiltersTest {
    private static final QueryList LIST = new QueryList(
            "test.filters",
            "test.filters",
            "view",
            "r.id",
            "rows r",
            "r.id",
            List.of(QueryField.of("id", "ID", QueryFieldType.NUMBER, "r.id").asSortable()),
            "id");
    private static final String CONDITION = "{\"field\":\"id\",\"op\":\"gte\",\"value\":1}";

    @Test
    void narrowsSavedOrGroupsWithoutReplacingThem() {
        String saved = "[{\"any\":[" + CONDITION + "," + CONDITION + "]}]";
        String combined = ReportFilters.and(saved, "[" + CONDITION + "]");
        var root = JsonMapper.shared().readTree(combined);
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.get(0).has("any")).isTrue();
        assertThat(QueryAggregates.compile(LIST, (String) null, null, combined)
                        .filter()
                        .conditions())
                .hasSize(3);
    }

    @Test
    void enforcesOneCombinedConditionBudget() {
        String twenty = "[" + String.join(",", Collections.nCopies(20, CONDITION)) + "]";
        assertThatThrownBy(() -> QueryAggregates.compile(
                        LIST, (String) null, null, ReportFilters.and(twenty, "[" + CONDITION + "]")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsMalformedAndNonArrayNarrowing() {
        assertThatThrownBy(() -> ReportFilters.and("[]", "broken")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ReportFilters.and("{}", "[]")).isInstanceOf(ApiException.class);
    }

    @Test
    void capsBothInputAndCombinedCharacters() {
        assertThatThrownBy(() -> ReportFilters.and("[]", " ".repeat(16_384) + "[]"))
                .isInstanceOf(ApiException.class);
        String large = "[{\"field\":\"id\",\"op\":\"eq\",\"value\":\"" + "x".repeat(9000) + "\"}]";
        assertThatThrownBy(() -> ReportFilters.and(large, large)).isInstanceOf(ApiException.class);
    }

    @Test
    void emptyPeriodKeepsSavedFilterAndEmptySavedFilterKeepsPeriod() {
        assertThat(ReportFilters.and(CONDITION, null)).isEqualTo(CONDITION);
        assertThat(ReportFilters.and(null, "[" + CONDITION + "]")).isEqualTo("[" + CONDITION + "]");
    }
}
