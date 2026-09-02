package com.greenwhite.dwh.instance.ms.task.pref;

/**
 * Tasks & Projects Preferences and Constants (MsTaskPref).
 */
public final class MsTaskPref {
    private MsTaskPref() {}

    public static final String MODULE_CODE = "ms.task";

    // Task Involve Kinds (Roles within task)
    public static final String INVOLVE_RESPONSIBLE = "R";
    public static final String INVOLVE_EXECUTOR = "E";
    public static final String INVOLVE_PARTICIPANT = "P";
    public static final String INVOLVE_AUTHOR = "A";
    public static final String INVOLVE_OBSERVER = "O";

    // Task Status Pcodes
    public static final String STATUS_NEW = "new";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_CANCELLED = "cancelled";

    // Task Priorities
    public static final String PRIORITY_LOW = "low";
    public static final String PRIORITY_MEDIUM = "medium";
    public static final String PRIORITY_HIGH = "high";
    public static final String PRIORITY_CRITICAL = "critical";

    // Forms
    public static final String FORM_PROJECTS = "tasks.projects";
    public static final String FORM_TASKS = "tasks.items";
    public static final String FORM_COMMENTS = "tasks.comments";
}
