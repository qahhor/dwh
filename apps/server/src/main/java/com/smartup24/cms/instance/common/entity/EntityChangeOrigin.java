package com.smartup24.cms.instance.common.entity;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Who, besides the signed-in person, makes the changes of the work in progress (ADR-0046): an automation of a live form
 * runs its actions as its author, and every change it makes names it — the audit row of the change keeps the
 * automation's name ({@code _automation}, shown by the history next to the author) and the automations that the change
 * triggers learn where it came from: an automation is never triggered by its own changes, and a chain of automations
 * stops at its depth limit. Held per thread for the length of {@link #with}.
 */
public final class EntityChangeOrigin {
    /** The property of an audit row that names the automation of the change. */
    public static final String AUDIT_KEY = "_automation";

    private static final ThreadLocal<Origin> CURRENT = new ThreadLocal<>();

    private EntityChangeOrigin() {}

    /**
     * An automation at work.
     *
     * @param automationId the automation
     * @param name         its name, as the history shows it
     * @param depth        the place of its run in a chain of automations, from 1
     */
    public record Origin(long automationId, String name, int depth) {}

    /** The automation whose actions make the changes now, if any. */
    public static Optional<Origin> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Runs {@code work} as the work of {@code origin}, then restores what was before. */
    public static <T> T with(Origin origin, Supplier<T> work) {
        Origin before = CURRENT.get();
        CURRENT.set(origin);
        try {
            return work.get();
        } finally {
            if (before == null) CURRENT.remove();
            else CURRENT.set(before);
        }
    }
}
