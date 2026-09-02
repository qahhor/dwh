package com.greenwhite.dwh.instance.ms.notify.pref;

/**
 * Notifications and Outbox Preferences and Constants (MsNotifyPref).
 */
public final class MsNotifyPref {
    private MsNotifyPref() {}

    public static final String MODULE_CODE = "ms.notify";

    // Outbox Statuses
    public static final String OUTBOX_PENDING = "PENDING";
    public static final String OUTBOX_PROCESSING = "PROCESSING";
    public static final String OUTBOX_SENT = "SENT";
    public static final String OUTBOX_FAILED = "FAILED";
    public static final String OUTBOX_DEAD_LETTER = "DEAD_LETTER";

    // Notification Types
    public static final String TYPE_INFO = "info";
    public static final String TYPE_SUCCESS = "success";
    public static final String TYPE_WARNING = "warning";
    public static final String TYPE_DANGER = "danger";

    // Forms
    public static final String FORM_INBOX = "notify.inbox";
    public static final String FORM_PREFERENCES = "notify.preferences";
    public static final String FORM_DEAD_LETTER = "notify.dead_letter";
    public static final String FORM_ANNOUNCEMENTS = "platform.announcements";
}
