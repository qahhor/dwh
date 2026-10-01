package com.smartup24.cms.instance.fnd.api;

/**
 * Who performs an operation on foundation tables, and setting that actor on the database session. The audit trigger
 * of the {@code fnd_*} tables requires a numeric {@code app.user_id} on every change, so a caller applies its actor
 * inside its own transaction before it writes.
 *
 * <p>Part of the foundation's contract (plan 10/10, item 4.2): callers depend on this interface, not on its
 * implementation, which may move to another package.
 */
public interface FndActorContext {

    /** The technical account of jobs and seeds; the journals record it as {@code system}. */
    FndActor system();

    /** A user actor: the journals record the user id as text. */
    FndActor user(long userId);

    /**
     * Sets {@code app.user_id} for the current transaction only, so it must be called inside one: otherwise the
     * setting is lost with the connection and the audit trigger refuses the change.
     */
    void apply(FndActor actor);
}
