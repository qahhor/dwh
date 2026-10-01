package com.smartup24.cms.instance.kauth.pref;

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
    /** The session cookie the server sets (plan 10/10, item 4.7). */
    public static final String SESSION_COOKIE_NAME = "SMC_SESSION";
    /** Seven days: the lifetime of the session cookie in the browser. */
    public static final int SESSION_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 7;
    /** The prefix of every personal API token; a bearer value without it is not looked up (plan 10/10, item 4.7). */
    public static final String API_TOKEN_PREFIX = "smc_";

    public static final int OTP_CODE_LENGTH = 6;
    public static final int MAX_OTP_ATTEMPTS = 3;
    public static final int OTP_EXPIRATION_MINUTES = 5;
    public static final int RESET_CODE_EXPIRATION_HOURS = 24;
}
