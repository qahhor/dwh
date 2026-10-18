package com.smartup24.cms.instance.common.formula;

import com.smartup24.cms.instance.common.web.RequestZone;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * What a formula reads besides the record (ADR-0042): the moment of the evaluation and the time zone of the request,
 * which {@code today()}, {@code now()}, the date parts of a moment and {@code dateOf} use.
 */
public record FormulaScope(ZoneId zone, Instant now) {
    /** The scope of the work in progress: the request's time zone, the clock's moment. */
    public static FormulaScope current(Clock clock) {
        return new FormulaScope(RequestZone.current(), clock.instant());
    }
}
