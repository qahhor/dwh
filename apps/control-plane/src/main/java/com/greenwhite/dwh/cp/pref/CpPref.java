package com.greenwhite.dwh.cp.pref;

/** Константы control plane (конвенция *Pref, как в модулях экземпляра). */
public final class CpPref {
    private CpPref() {}

    public static final String MODULE_CODE = "cp";

    public static final String SESSION_COOKIE_NAME = "CP_SESSION";
    public static final int SESSION_TTL_DAYS = 7;

    // Роли (FR-CP-7). Права упрощённые, без матрицы форм экземпляра —
    // сотрудников платформы единицы, полноценный RBAC здесь избыточен (ADR-0006 2.4).
    public static final String ROLE_ADMIN = "cp-admin";       // всё
    public static final String ROLE_ENGINEER = "cp-engineer"; // флот, версии, бэкапы; лицензии — просмотр
    public static final String ROLE_EDITOR = "cp-editor";     // объявления

    public static final String STATE_ACTIVE = "A";
    public static final String STATE_PASSIVE = "P";

    /** Экземпляр считается недоступным, если heartbeat не приходил дольше этого срока. */
    public static final int HEARTBEAT_TIMEOUT_MINUTES = 10;
}
