package com.smartup24.cms.instance.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The time zone a formula's {@code today()} and the date parts of a moment are read in (ADR-0042): the zone a
 * background work names, else the request's {@code X-Time-Zone} header — the web application sends the viewer's zone,
 * the one its live preview uses — when it names a region of the zone database, else UTC. A bad header is not an error: the
 * zone only shifts what "today" is, which the viewer could type themselves.
 */
public final class RequestZone {
    /** The header the web application names the viewer's time zone in. */
    public static final String HEADER = "X-Time-Zone";

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_+\\-/]{0,63}");
    private static final ThreadLocal<ZoneId> NAMED = new ThreadLocal<>();

    private RequestZone() {}

    /** The zone of the work in progress. */
    public static ZoneId current() {
        ZoneId named = NAMED.get();
        if (named != null) return named;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return of(attributes.getRequest());
        }
        return ZoneOffset.UTC;
    }

    /** Runs {@code work} in {@code zone}, then restores the zone before. */
    public static <T> T with(ZoneId zone, Supplier<T> work) {
        ZoneId before = NAMED.get();
        NAMED.set(zone);
        try {
            return work.get();
        } finally {
            if (before == null) NAMED.remove();
            else NAMED.set(before);
        }
    }

    static ZoneId of(HttpServletRequest request) {
        return parse(request.getHeader(HEADER));
    }

    /** The zone a header names — a region of the zone database ({@code Asia/Tashkent}) — UTC for anything else. */
    static ZoneId parse(@Nullable String header) {
        if (header == null
                || !NAME.matcher(header).matches()
                || !ZoneId.getAvailableZoneIds().contains(header)) {
            return ZoneOffset.UTC;
        }
        return ZoneId.of(header);
    }
}
