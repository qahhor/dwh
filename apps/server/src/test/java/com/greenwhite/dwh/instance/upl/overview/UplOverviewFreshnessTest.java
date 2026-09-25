package com.greenwhite.dwh.instance.upl.overview;

import com.greenwhite.dwh.instance.upl.overview.UplOverviewRepository.SourceFreshnessRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Свежесть источника по периодичности и сроку сдачи (роадмап п. 25). */
class UplOverviewFreshnessTest {

    private static SourceFreshnessRow source(String periodicity, int slaDays, LocalDate last) {
        return new SourceFreshnessRow(1L, "tax", "Налоги", periodicity, slaDays, last, null);
    }

    @Test
    @DisplayName("месяц: до конца следующего периода — свежие, после — ждём в пределах срока, потом просрочено")
    void monthlySourceMovesFromFreshToDueToOverdue() {
        SourceFreshnessRow july = source("month", 5, LocalDate.of(2026, 7, 31));

        var fresh = UplOverviewService.freshness(july, LocalDate.of(2026, 8, 31));
        assertThat(fresh.state()).isEqualTo("fresh");
        assertThat(fresh.expectedPeriodTo()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(fresh.dueBy()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(UplOverviewService.freshness(july, LocalDate.of(2026, 9, 5)).state()).isEqualTo("due");
        assertThat(UplOverviewService.freshness(july, LocalDate.of(2026, 9, 6)).state()).isEqualTo("overdue");
    }

    @Test
    @DisplayName("следующий период кончается в последний день месяца; квартал и год считаются так же")
    void nextPeriodEndsOnTheLastDayOfItsMonth() {
        assertThat(UplOverviewService.nextPeriodEnd(LocalDate.of(2026, 1, 31), "month")).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(UplOverviewService.nextPeriodEnd(LocalDate.of(2026, 2, 28), "month")).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(UplOverviewService.nextPeriodEnd(LocalDate.of(2026, 3, 31), "quarter")).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(UplOverviewService.nextPeriodEnd(LocalDate.of(2025, 12, 31), "year")).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("источник без поставок — never, разовый — adhoc, у обоих нет срока")
    void neverAndAdhocHaveNoDeadline() {
        var never = UplOverviewService.freshness(source("month", 5, null), LocalDate.of(2026, 9, 25));
        assertThat(never.state()).isEqualTo("never");
        assertThat(never.dueBy()).isNull();
        var adhoc = UplOverviewService.freshness(source("adhoc", 0, LocalDate.of(2026, 1, 1)), LocalDate.of(2026, 9, 25));
        assertThat(adhoc.state()).isEqualTo("adhoc");
        assertThat(adhoc.expectedPeriodTo()).isNull();
    }
}
