package com.smartup24.cms.instance.ms.notify.provider;

import org.jspecify.annotations.Nullable;

/**
 * What a stub channel may write about a recipient: enough to tell two messages apart, never the address itself. A
 * message body is never written at all: it carries invitation and reset links and one-time codes, and the log of a
 * production server is read by more people than the recipient.
 */
final class StubRecipients {

    private StubRecipients() {}

    /** {@code u***@example.com}, {@code ***42}: the first letter and the domain of an e-mail, else the last two. */
    static String mask(@Nullable String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "-";
        }
        String value = recipient.strip();
        int at = value.indexOf('@');
        if (at > 0) {
            return value.charAt(0) + "***" + value.substring(at);
        }
        return value.length() <= 2 ? "***" : "***" + value.substring(value.length() - 2);
    }

    /** The length of a text, without the text. */
    static int length(@Nullable String text) {
        return text == null ? 0 : text.length();
    }
}
