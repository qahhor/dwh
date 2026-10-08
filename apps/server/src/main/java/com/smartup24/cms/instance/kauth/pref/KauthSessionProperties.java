package com.smartup24.cms.instance.kauth.pref;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Lifetime of a cookie session (FR-AUTH-8, ADR-0034, plan 10/10, item 7.5).
 *
 * <ul>
 *   <li>{@code absoluteTtl} — a session is valid at most this long after sign-in, whatever its activity; the cookie
 *       Max-Age equals it.
 *   <li>{@code idleTimeout} — a session unused for this long is no longer valid.
 *   <li>{@code touchInterval} — the last activity is written at most once per interval, not on every request.
 *   <li>{@code cleanupInterval} — how often the housekeeping worker closes expired sessions; validity never depends
 *       on it.
 * </ul>
 *
 * Both limits are part of the SQL condition of an active session. A missing value takes its default; an
 * inconsistent set stops the start.
 */
@Validated
@ConfigurationProperties(prefix = "smc.session")
public record KauthSessionProperties(
        Duration absoluteTtl, Duration idleTimeout, Duration touchInterval, Duration cleanupInterval) {

    public static final Duration DEFAULT_ABSOLUTE_TTL = Duration.ofDays(7);
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofHours(12);
    public static final Duration DEFAULT_TOUCH_INTERVAL = Duration.ofMinutes(1);
    public static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofHours(1);

    public KauthSessionProperties {
        absoluteTtl = positiveOr(absoluteTtl, DEFAULT_ABSOLUTE_TTL, "absolute-ttl");
        idleTimeout = positiveOr(idleTimeout, DEFAULT_IDLE_TIMEOUT, "idle-timeout");
        touchInterval = positiveOr(touchInterval, DEFAULT_TOUCH_INTERVAL, "touch-interval");
        cleanupInterval = positiveOr(cleanupInterval, DEFAULT_CLEANUP_INTERVAL, "cleanup-interval");
        if (idleTimeout.compareTo(absoluteTtl) > 0) {
            throw new IllegalArgumentException("smc.session.idle-timeout must not exceed smc.session.absolute-ttl");
        }
        if (touchInterval.compareTo(idleTimeout) >= 0) {
            throw new IllegalArgumentException("smc.session.touch-interval must be shorter than idle-timeout");
        }
    }

    /** The defaults, for code built without the application configuration. */
    public static KauthSessionProperties defaults() {
        return new KauthSessionProperties(null, null, null, null);
    }

    /** The cookie Max-Age in seconds: the absolute lifetime of the session. */
    public long cookieMaxAgeSeconds() {
        return absoluteTtl.toSeconds();
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("smc.session." + name + " must be positive");
        }
        return value;
    }
}
