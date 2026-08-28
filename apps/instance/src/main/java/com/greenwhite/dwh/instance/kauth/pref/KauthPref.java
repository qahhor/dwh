package com.greenwhite.dwh.instance.kauth.pref;

/**
 * Kernel Auth Preferences and Constants (KauthPref).
 */
public final class KauthPref {
    private KauthPref() {}

    public static final String MODULE_CODE = "kauth";

    // Channels
    public static final String CHANNEL_TELEGRAM = "telegram";
    public static final String CHANNEL_SMS = "sms";
    public static final String CHANNEL_EMAIL = "email";

    // Session Constants
    public static final String SESSION_COOKIE_NAME = "DWH_SESSION";
    public static final int OTP_CODE_LENGTH = 6;
    public static final int MAX_OTP_ATTEMPTS = 3;
    public static final int OTP_EXPIRATION_MINUTES = 5;
    public static final int RESET_CODE_EXPIRATION_HOURS = 24;
}
