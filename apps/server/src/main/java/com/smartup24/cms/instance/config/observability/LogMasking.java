package com.smartup24.cms.instance.config.observability;

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The safety net against secrets in log lines (plan 10/10, item 7.1; ADR-0009, section 5). The rule stays the
 * author's: a log line carries identifiers, never the values of secrets. This masks what slips through anyway:
 *
 * <ul>
 *   <li>a member whose name says it is a secret (password, secret, token, authorization, cookie, otp, api key,
 *       credentials, a bare {@code code}) loses its value entirely;
 *   <li>inside any text value, {@code key=value}, {@code key: value} and {@code "key":"value"} pairs with such a key
 *       keep the key and lose the value; so do the token of invitation and reset links ({@code #token=...}), an OAuth
 *       {@code ?code=...}, a {@code Bearer}/{@code Basic} credential and the whole value of a cookie header.
 * </ul>
 */
public final class LogMasking {

    /** What replaces a masked value. */
    public static final String MASK = "***";

    private static final String SECRET_WORDS =
            "password|passwd|pwd|secret|token|authorization|cookie|otp|credential|api[-_.]?key|apikey";

    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(" + SECRET_WORDS + ").*|code");

    private static final String NOT_AFTER_WORD = "(?<![A-Za-z0-9])";
    private static final String SEPARATOR = "(\\s*[\"']?\\s*[:=]\\s*[\"']?)";

    /** A cookie header keeps nothing of its value: all pairs up to the end of the line or a quote. */
    private static final Pattern COOKIE =
            Pattern.compile("(?i)" + NOT_AFTER_WORD + "((?:set-)?cookie)" + SEPARATOR + "([^\\r\\n\"']+)");

    private static final Pattern PAIR = Pattern.compile("(?i)" + NOT_AFTER_WORD
            + "([a-z0-9_-]*(?:password|passwd|pwd|secret|token)|(?:proxy-)?authorization|otp(?:[_-]?code)?"
            + "|api[-_.]?key|apikey|credentials?)"
            + SEPARATOR
            + "((?:bearer|basic|digest|negotiate)\\s+)?([^\\s\"',;&}\\])]+)");

    private static final Pattern URL_CODE = Pattern.compile("(?i)([?&#]code=)[^&#\\s\"']+");

    private static final Pattern BARE_CREDENTIAL = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");

    private LogMasking() {}

    /** True when a member with this name carries a secret by its name alone. */
    public static boolean isSecretName(@Nullable String name) {
        return name != null
                && SECRET_NAME.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    /** The text with the values of secret pairs, link tokens and credentials replaced by {@link #MASK}. */
    public static @Nullable String maskText(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = COOKIE.matcher(text).replaceAll("$1$2" + MASK);
        masked = PAIR.matcher(masked).replaceAll("$1$2$3" + MASK);
        masked = URL_CODE.matcher(masked).replaceAll("$1" + MASK);
        return BARE_CREDENTIAL.matcher(masked).replaceAll("$1 " + MASK);
    }

    /** One member of a structured log line: masked by its name, then by its text. */
    public static @Nullable Object maskMember(@Nullable String name, @Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (isSecretName(name)) {
            return MASK;
        }
        return value instanceof CharSequence text ? maskText(text.toString()) : value;
    }
}
