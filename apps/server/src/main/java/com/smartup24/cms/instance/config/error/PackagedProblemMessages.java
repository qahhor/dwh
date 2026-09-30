package com.smartup24.cms.instance.config.error;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Error texts from the catalogs packaged with the build (ru, en, uz), without the database: the fallback of
 * {@link CatalogProblemMessages} and the renderer of a context that has no i18n service (web slice tests). The
 * language is the first one of {@code Accept-Language} when it is packaged, otherwise Russian.
 */
public final class PackagedProblemMessages implements ProblemMessages {

    static final String FALLBACK_LANGUAGE = "ru";
    private static final List<String> LANGUAGES = List.of("ru", "en", "uz");

    private final boolean russianOnly;

    public PackagedProblemMessages() {
        this(false);
    }

    private PackagedProblemMessages(boolean russianOnly) {
        this.russianOnly = russianOnly;
    }

    /** Russian whatever the request asks: what a request without {@code Accept-Language} gets. */
    public static ProblemMessages russian() {
        return new PackagedProblemMessages(true);
    }

    @Override
    public String render(HttpServletRequest request, String key, Map<String, ?> params) {
        return fill(text(russianOnly ? FALLBACK_LANGUAGE : language(request), key), params);
    }

    /** The text of the key in a packaged language, falling back to Russian, then to the key itself. */
    static String text(String language, String key) {
        Map<String, Map<String, String>> catalogs = Catalogs.ALL;
        String text = catalogs.getOrDefault(language, Map.of()).get(key);
        return text != null ? text : catalogs.get(FALLBACK_LANGUAGE).getOrDefault(key, key);
    }

    static String language(HttpServletRequest request) {
        String requested = requestedLanguage(request);
        return requested != null && LANGUAGES.contains(requested) ? requested : FALLBACK_LANGUAGE;
    }

    /** The primary language of the first {@code Accept-Language} entry, or null. */
    static String requestedLanguage(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return null;
        }
        String first = header.split(",", 2)[0].split(";", 2)[0].trim();
        String language = first.split("-", 2)[0].toLowerCase(Locale.ROOT);
        return language.isEmpty() || "*".equals(language) ? null : language;
    }

    static String fill(String template, Map<String, ?> params) {
        String text = template;
        for (var param : params.entrySet()) {
            text = text.replace("{" + param.getKey() + "}", String.valueOf(param.getValue()));
        }
        return text;
    }

    /** Loaded once, on first use. */
    private static final class Catalogs {
        static final Map<String, Map<String, String>> ALL =
                LANGUAGES.stream().collect(Collectors.toUnmodifiableMap(Function.identity(), Catalogs::load));

        private static Map<String, String> load(String language) {
            try (InputStream in = PackagedProblemMessages.class.getResourceAsStream("/i18n/" + language + ".json")) {
                if (in == null) {
                    return Map.of();
                }
                // Read before Spring or without it (slices, the error path itself): the shared default mapper.
                return Map.copyOf(JsonMapper.shared().readValue(in, new TypeReference<Map<String, String>>() {}));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
