package com.smartup24.cms.instance.audit.api;

import java.time.Instant;

/** The flat filters of the security event list, kept for existing callers next to the registry filter. */
public record SecurityEventFilter(String eventType, Long userId, String ip, Instant from, Instant to) {

    public static SecurityEventFilter none() {
        return new SecurityEventFilter(null, null, null, null, null);
    }
}
