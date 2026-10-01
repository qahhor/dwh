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
    /**
     * The session cookie name before plan 10/10, item 4.7: still read until {@code ApiDeprecations.SUNSET} and
     * replaced by {@link #SESSION_COOKIE_NAME} on the response that reads it.
     */
    public static final String LEGACY_SESSION_COOKIE_NAME = "DWH_SESSION";
    /** Seven days: the lifetime of the session cookie in the browser. */
    public static final int SESSION_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 7;
    /** The prefix of a newly issued personal API token (plan 10/10, item 4.7). */
    public static final String API_TOKEN_PREFIX = "smc_";
    /**
     * The prefix of tokens issued before plan 10/10, item 4.7. A token is looked up by its hash, never by its
     * prefix, so such tokens keep authenticating until {@code ApiDeprecations.SUNSET}.
     */
    public static final String LEGACY_API_TOKEN_PREFIX = "dwh_";

    public static final int OTP_CODE_LENGTH = 6;
    public static final int MAX_OTP_ATTEMPTS = 3;
    public static final int OTP_EXPIRATION_MINUTES = 5;
    public static final int RESET_CODE_EXPIRATION_HOURS = 24;
}
