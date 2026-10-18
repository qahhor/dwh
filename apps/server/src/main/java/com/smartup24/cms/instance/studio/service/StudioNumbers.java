package com.smartup24.cms.instance.studio.service;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The number of a document of a live form (ADR-0043): the period its counter runs in and the text a pattern gives a
 * counter value. {@code {YYYY}} is the year, {@code {YY}} its last two digits, {@code {MM}} the month, {@code {N...}}
 * the counter with at least as many digits as N's — a larger counter keeps all its digits, so a number never repeats.
 */
final class StudioNumbers {
    private static final Pattern TOKEN = Pattern.compile("\\{(YYYY|YY|MM|N{1,12})}");

    private StudioNumbers() {}

    /** The period of the counter on {@code day}: none, the year ({@code 2026}) or the month ({@code 2026-10}). */
    static String period(String reset, LocalDate day) {
        return switch (reset) {
            case "year" -> String.format(Locale.ROOT, "%04d", day.getYear());
            case "month" -> String.format(Locale.ROOT, "%04d-%02d", day.getYear(), day.getMonthValue());
            default -> "";
        };
    }

    /** The number the pattern gives the counter value {@code counter} on {@code day}. */
    static String format(String pattern, LocalDate day, long counter) {
        Matcher tokens = TOKEN.matcher(pattern);
        StringBuilder number = new StringBuilder();
        while (tokens.find()) {
            String token = tokens.group(1);
            String value = switch (token) {
                case "YYYY" -> String.format(Locale.ROOT, "%04d", day.getYear());
                case "YY" -> String.format(Locale.ROOT, "%02d", day.getYear() % 100);
                case "MM" -> String.format(Locale.ROOT, "%02d", day.getMonthValue());
                default -> padded(counter, token.length());
            };
            tokens.appendReplacement(number, Matcher.quoteReplacement(value));
        }
        tokens.appendTail(number);
        return number.toString();
    }

    private static String padded(long counter, int digits) {
        String text = Long.toString(counter);
        return text.length() >= digits ? text : "0".repeat(digits - text.length()) + text;
    }
}
