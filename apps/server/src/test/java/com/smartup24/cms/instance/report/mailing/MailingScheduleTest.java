package com.smartup24.cms.instance.report.mailing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0045: the slots of a mailing's schedule, across months, weekdays and clock changes. */
class MailingScheduleTest {
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    @DisplayName("ADR-0045: a daily slot is the next local time, never the one just past")
    void daily() {
        var schedule = new MailingSchedule("daily", LocalTime.of(8, 0), TASHKENT, null, null);
        // 2026-10-19 08:00 in Tashkent is 03:00 UTC.
        assertThat(schedule.nextAfter(Instant.parse("2026-10-19T02:59:00Z"))).isEqualTo("2026-10-19T03:00:00Z");
        assertThat(schedule.nextAfter(Instant.parse("2026-10-19T03:00:00Z"))).isEqualTo("2026-10-20T03:00:00Z");
        assertThat(schedule.latestAtOrBefore(Instant.parse("2026-10-19T03:00:00Z")))
                .isEqualTo("2026-10-19T03:00:00Z");
        assertThat(schedule.latestAtOrBefore(Instant.parse("2026-10-19T02:00:00Z")))
                .isEqualTo("2026-10-18T03:00:00Z");
    }

    @Test
    @DisplayName("ADR-0045: a weekly slot falls on its weekday")
    void weekly() {
        // 2026-10-19 is a Monday; Friday is 5.
        var schedule = new MailingSchedule("weekly", LocalTime.of(9, 30), TASHKENT, 5, null);
        assertThat(schedule.nextAfter(Instant.parse("2026-10-19T00:00:00Z"))).isEqualTo("2026-10-23T04:30:00Z");
        assertThat(schedule.matches(LocalDate.of(2026, 10, 23))).isTrue();
        assertThat(schedule.matches(LocalDate.of(2026, 10, 22))).isFalse();
    }

    @Test
    @DisplayName("ADR-0045: a day of the month past the month's end is its last day")
    void monthlyLastDay() {
        var schedule = new MailingSchedule("monthly", LocalTime.of(7, 0), TASHKENT, null, 31);
        assertThat(schedule.nextAfter(Instant.parse("2027-02-01T00:00:00Z"))).isEqualTo("2027-02-28T02:00:00Z");
        assertThat(schedule.nextAfter(Instant.parse("2028-02-01T00:00:00Z"))).isEqualTo("2028-02-29T02:00:00Z");
        assertThat(schedule.nextAfter(Instant.parse("2026-04-01T00:00:00Z"))).isEqualTo("2026-04-30T02:00:00Z");
        assertThat(schedule.matches(LocalDate.of(2026, 5, 30))).isFalse();
        assertThat(schedule.matches(LocalDate.of(2026, 5, 31))).isTrue();
    }

    @Test
    @DisplayName("ADR-0045: a time the clocks skip is sent after the change, a repeated time once, at its first")
    void clockChanges() {
        var schedule = new MailingSchedule("daily", LocalTime.of(2, 30), BERLIN, null, null);
        // 2026-03-29: 02:00 CET becomes 03:00 CEST; 02:30 does not exist and becomes 03:30 CEST = 01:30 UTC.
        assertThat(schedule.slotOn(LocalDate.of(2026, 3, 29))).isEqualTo("2026-03-29T01:30:00Z");
        // 2026-10-25: 03:00 CEST becomes 02:00 CET; 02:30 happens twice, the first is CEST = 00:30 UTC.
        assertThat(schedule.slotOn(LocalDate.of(2026, 10, 25))).isEqualTo("2026-10-25T00:30:00Z");
        assertThat(schedule.nextAfter(Instant.parse("2026-10-25T00:30:00Z"))).isEqualTo("2026-10-26T01:30:00Z");
    }

    @Test
    @DisplayName("ADR-0045: after downtime the latest missed slot is the one sent")
    void latestMissedSlot() {
        var schedule = new MailingSchedule("daily", LocalTime.of(8, 0), TASHKENT, null, null);
        Instant afterThreeDays = Instant.parse("2026-10-22T05:00:00Z");
        assertThat(schedule.latestAtOrBefore(afterThreeDays)).isEqualTo("2026-10-22T03:00:00Z");
        assertThat(schedule.nextAfter(afterThreeDays)).isEqualTo("2026-10-23T03:00:00Z");
    }

    @Test
    @DisplayName("ADR-0045: an incomplete schedule is refused")
    void incomplete() {
        assertThatThrownBy(() -> new MailingSchedule("weekly", LocalTime.NOON, TASHKENT, null, null))
                .hasMessage("weekday");
        assertThatThrownBy(() -> new MailingSchedule("monthly", LocalTime.NOON, TASHKENT, null, 32))
                .hasMessage("monthDay");
        assertThatThrownBy(() -> new MailingSchedule("hourly", LocalTime.NOON, TASHKENT, null, null))
                .hasMessage("frequency");
    }
}
