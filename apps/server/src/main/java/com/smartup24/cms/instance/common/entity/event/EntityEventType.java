package com.smartup24.cms.instance.common.entity.event;

import java.util.Locale;

/** What happened to an entity record (ADR-0032, 6.9). */
public enum EntityEventType {
    CREATED,
    UPDATED,
    DELETED,
    ARCHIVED,
    RESTORED,
    /** A declared record action ran; the event is named by the action's code. */
    ACTION;

    /** The name in an event's type: {@code created}, {@code updated}… */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
