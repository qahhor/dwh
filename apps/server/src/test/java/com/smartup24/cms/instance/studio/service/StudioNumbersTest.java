package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The numbers of the documents of live forms (ADR-0043): the period of the counter and the text of a number. */
class StudioNumbersTest {
    private static final LocalDate DAY = LocalDate.of(2026, 3, 7);

    @Test
    void theCounterRunsInTheResetPeriod() {
        assertThat(StudioNumbers.period("never", DAY)).isEmpty();
        assertThat(StudioNumbers.period("year", DAY)).isEqualTo("2026");
        assertThat(StudioNumbers.period("month", DAY)).isEqualTo("2026-03");
    }

    @Test
    void thePatternGivesTheNumberAndALargerCounterKeepsAllItsDigits() {
        assertThat(StudioNumbers.format("ЗК-{YYYY}-{NNNNNN}", DAY, 15)).isEqualTo("ЗК-2026-000015");
        assertThat(StudioNumbers.format("{YY}{MM}/{NN}", DAY, 7)).isEqualTo("2603/07");
        assertThat(StudioNumbers.format("A-{NN}", DAY, 1234)).isEqualTo("A-1234");
        assertThat(StudioNumbers.format("$-{N}", DAY, 3)).isEqualTo("$-3");
    }
}
