package com.smartup24.cms.instance.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The language the server writes names in, where the names are data rather than translation keys — the items of a
 * reference list an administrator keeps (ADR-0041): the language a background job names for its work, else the first
 * {@code Accept-Language} of the request (the web application sends the user's) when it is ru, uz or en, else ru.
 */
public final class RequestLanguage {
    /** The languages of the product, Russian first. */
    public static final List<String> LANGUAGES = List.of("ru", "uz", "en");

    private static final ThreadLocal<String> NAMED = new ThreadLocal<>();

    private RequestLanguage() {}

    /** The language of the work in progress. */
    public static String current() {
        String named = NAMED.get();
        if (named != null) return named;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return of(attributes.getRequest());
        }
        return LANGUAGES.getFirst();
    }

    /** Runs {@code work} in {@code language} (a product language, else Russian), then restores the language before. */
    public static <T> T with(@Nullable String language, Supplier<T> work) {
        String before = NAMED.get();
        NAMED.set(known(language));
        try {
            return work.get();
        } finally {
            if (before == null) NAMED.remove();
            else NAMED.set(before);
        }
    }

    /** Runs {@code work} in {@code language}, like {@link #with}. */
    public static void run(@Nullable String language, Runnable work) {
        with(language, () -> {
            work.run();
            return Boolean.TRUE;
        });
    }

    static String of(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) return LANGUAGES.getFirst();
        String first = header.split(",", 2)[0].split(";", 2)[0].trim();
        return known(first.split("-", 2)[0]);
    }

    private static String known(@Nullable String language) {
        String lower = language == null ? "" : language.toLowerCase(Locale.ROOT);
        return LANGUAGES.contains(lower) ? lower : LANGUAGES.getFirst();
    }
}
