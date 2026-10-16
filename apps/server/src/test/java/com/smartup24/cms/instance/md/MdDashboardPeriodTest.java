package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.md.service.MdDashboardPeriod;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MdDashboardPeriodTest {
    private final JsonMapper json = JsonMapper.shared();
    private final EntityReports reports = mock(EntityReports.class);
    private final MdDashboardPeriod period = new MdDashboardPeriod(reports, json);
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Test
    void dateBoundsAreInclusiveAndTimestampBoundsUseUtcExclusiveNextDay() {
        when(reports.list("test.records", null)).thenReturn(list());
        var dates = json.readTree(period.filter("test.records", null, "onDate", FROM, TO));
        assertThat(dates.get(0).get("op").asString()).isEqualTo("between");
        assertThat(dates.get(0).get("value").toString()).isEqualTo("[\"2026-10-01\",\"2026-10-31\"]");
        var instants = json.readTree(period.filter("test.records", null, "atTime", FROM, TO));
        assertThat(instants.get(0).get("op").asString()).isEqualTo("gte");
        assertThat(instants.get(0).get("value").asString()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(instants.get(1).get("op").asString()).isEqualTo("lt");
        assertThat(instants.get(1).get("value").asString()).isEqualTo("2026-11-01T00:00:00Z");
    }

    @Test
    void invalidOrPartlySpecifiedPeriodsAre422IncludingLocalDateMax() {
        when(reports.list("test.records", null)).thenReturn(list());
        assertThat(period.filter("test.records", null, null, null, null)).isNull();
        assertThatThrownBy(() -> period.filter("test.records", null, "atTime", FROM, LocalDate.MAX))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> period.filter("test.records", null, "atTime", LocalDate.MIN, TO))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> period.filter("test.records", null, "atTime", TO, FROM))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> period.filter("test.records", null, "atTime", null, TO))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> period.filter("test.records", null, "atTime", FROM, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> period.filter("test.records", null, null, FROM, TO))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> period.filter("test.records", null, "atTime", FROM, LocalDate.of(9999, 12, 31)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void hiddenUnknownNonDateAndNonFilterableBindingsFailClosed() {
        when(reports.list("test.records", null)).thenReturn(list());
        for (String field : List.of("missing", "id", "hidden", "unfilterable")) {
            assertThatThrownBy(() -> period.field("test.records", null, field)).isInstanceOf(ApiException.class);
        }
    }

    private static QueryList list() {
        return new QueryList(
                "test.records",
                "test.records",
                "view",
                "t.id",
                "test_records t",
                "t.id",
                List.of(
                        QueryField.of("id", "id", QueryFieldType.NUMBER, "t.id").asSortable(),
                        QueryField.of("onDate", "date", QueryFieldType.DATE, "t.on_date"),
                        QueryField.of("atTime", "time", QueryFieldType.INSTANT, "t.at_time"),
                        QueryField.of("hidden", "hidden", QueryFieldType.DATE, "t.hidden")
                                .requires("test.secret", "view"),
                        QueryField.of("unfilterable", "no", QueryFieldType.DATE, "t.no")
                                .asNotFilterable()),
                "id");
    }
}
