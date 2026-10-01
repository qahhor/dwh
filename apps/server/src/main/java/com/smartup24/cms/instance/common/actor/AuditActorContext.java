package com.smartup24.cms.instance.common.actor;

/**
 * Who performs a change of an audited table, and setting that actor on the database session. The audit trigger
 * {@code fnd_audit_trigger} (V100) requires a numeric {@code app.user_id} on every change of a table it guards (the
 * units, the load ledger, the upload formats and packages), so a caller applies its actor inside its own transaction
 * before it writes.
 *
 * <p>A platform contract (plan 10/10, item 4.2): the modules depend on this interface; md, which owns the users,
 * implements it.
 */
public interface AuditActorContext {

    /** The technical account of jobs and seeds; the journals record it as {@code system}. */
    AuditActor system();

    /** A user actor: the journals record the user id as text. */
    AuditActor user(long userId);

    /**
     * Sets {@code app.user_id} for the current transaction only, so it must be called inside one: otherwise the
     * setting is lost with the connection and the audit trigger refuses the change.
     */
    void apply(AuditActor actor);
}
