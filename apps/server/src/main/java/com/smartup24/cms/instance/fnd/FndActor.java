package com.smartup24.cms.instance.fnd;

/**
 * Who performs a foundation operation. {@code userId} is the {@code md_users} row that the framework audit sees
 * in {@code app.user_id}; {@code name} is what goes into our own journals ({@code fnd_loads.applied_by},
 * {@code fnd_load_log.actor}): the user id as text, or {@code system} for jobs.
 */
public record FndActor(long userId, String name) {

    public FndActor {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId должен быть положительным id из md_users");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name актора не задан");
        }
    }

    /** A user actor: the journals record the user id as text. */
    public static FndActor user(long userId) {
        return new FndActor(userId, String.valueOf(userId));
    }
}
