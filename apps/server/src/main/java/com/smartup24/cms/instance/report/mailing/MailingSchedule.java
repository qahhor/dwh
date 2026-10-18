package com.smartup24.cms.instance.report.mailing;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * When a mailing goes out (ADR-0045): every day, on a weekday or on a day of the month, at a local time of a time zone.
 *
 * <p>The slots are local: a time a clock change skips (02:30 on the night the clocks go forward) is sent at the same
 * distance after the change (03:30), a time that happens twice is sent at its first occurrence, so every scheduled day
 * has exactly one slot. A day of the month past the month's end means its last day (31 — 30 April, 28 or 29 February).
 *
 * @param frequency {@code daily}, {@code weekly} or {@code monthly}
 * @param weekday   weekly: 1 Monday … 7 Sunday
 * @param monthDay  monthly: 1 … 31
 */
public record MailingSchedule(
        String frequency,
        LocalTime time,
        ZoneId zone,
        @Nullable Integer weekday,
        @Nullable Integer monthDay) {

    public static final String DAILY = "daily";
    public static final String WEEKLY = "weekly";
    public static final String MONTHLY = "monthly";
    /** A monthly schedule has a slot within any 31 days; a year of days bounds every search with room to spare. */
    private static final int HORIZON_DAYS = 400;

    public MailingSchedule {
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(zone, "zone");
        switch (frequency) {
            case DAILY -> {}
            case WEEKLY -> {
                if (weekday == null || weekday < 1 || weekday > 7) throw new IllegalArgumentException("weekday");
            }
            case MONTHLY -> {
                if (monthDay == null || monthDay < 1 || monthDay > 31) throw new IllegalArgumentException("monthDay");
            }
            default -> throw new IllegalArgumentException("frequency");
        }
    }

    /** Whether the day has a slot. */
    public boolean matches(LocalDate date) {
        return switch (frequency) {
            case WEEKLY -> date.getDayOfWeek() == DayOfWeek.of(Objects.requireNonNull(weekday));
            case MONTHLY -> date.getDayOfMonth() == Math.min(Objects.requireNonNull(monthDay), date.lengthOfMonth());
            default -> true;
        };
    }

    /** The slot of a day that has one, as an instant. */
    public Instant slotOn(LocalDate date) {
        return ZonedDateTime.ofLocal(date.atTime(time), zone, null).toInstant();
    }

    /** The first slot strictly after {@code moment}. */
    public Instant nextAfter(Instant moment) {
        LocalDate day = moment.atZone(zone).toLocalDate().minusDays(1);
        for (int step = 0; step < HORIZON_DAYS; step++, day = day.plusDays(1)) {
            if (matches(day)) {
                Instant slot = slotOn(day);
                if (slot.isAfter(moment)) return slot;
            }
        }
        throw new IllegalStateException("No slot within " + HORIZON_DAYS + " days");
    }

    /** The latest slot at or before {@code moment}. */
    public Instant latestAtOrBefore(Instant moment) {
        LocalDate day = moment.atZone(zone).toLocalDate().plusDays(1);
        for (int step = 0; step < HORIZON_DAYS; step++, day = day.minusDays(1)) {
            if (matches(day)) {
                Instant slot = slotOn(day);
                if (!slot.isAfter(moment)) return slot;
            }
        }
        throw new IllegalStateException("No slot within " + HORIZON_DAYS + " days");
    }
}
