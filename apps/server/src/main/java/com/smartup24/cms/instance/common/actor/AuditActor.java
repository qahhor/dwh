package com.smartup24.cms.instance.common.actor;

/**
 * Who performs a change of a table audited by {@code fnd_audit_trigger} (V100). {@code userId} is the {@code md_users}
 * row that the audit sees in {@code app.user_id}; {@code name} is what goes into the modules' own journals
 * (the warehouse load ledger: who applied a load, who wrote a log row): the user id as text, or {@code system} for
 * jobs.
 */
public record AuditActor(long userId, String name) {

    /** The login and journal name of the technical account of jobs and seeds. */
    public static final String SYSTEM = "system";

    public AuditActor {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId должен быть положительным id из md_users");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name актора не задан");
        }
    }

    /** A user actor: the journals record the user id as text. */
    public static AuditActor user(long userId) {
        return new AuditActor(userId, String.valueOf(userId));
    }
}
