package com.smartup24.cms.instance.common.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The request forms the API keeps for one release after it chose another (plan 10/10, item 3.4, ADR-0023): path
 * aliases, toggles replaced by an explicit {@code PUT}, and snake_case query parameters. One table serves the runtime
 * (the web filter answers them with {@code Deprecation}, {@code Sunset} and {@code Link}) and the API description (the
 * operations and parameters are marked deprecated), so both say the same.
 *
 * <p>{@link #CURRENT} is empty: the forms deprecated before the first release were removed with it (ADR-0023). The
 * machinery stays for the forms a later release deprecates; a new one is an entry of {@link #CURRENT}.
 */
public final class ApiDeprecations {

    /** The day the current forms were deprecated (RFC 9745 {@code Deprecation}). */
    public static final LocalDate DEPRECATED_SINCE = LocalDate.of(2026, 9, 29);

    /** The day they stop answering (RFC 8594 {@code Sunset}): the release after the one that deprecated them. */
    public static final LocalDate SUNSET = LocalDate.of(2026, 12, 31);

    /** Any method, in an {@link #alias} of a path. */
    public static final String ANY = "*";

    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)}");

    /** The forms deprecated now: none. Path aliases go the more specific first, as the first that matches wins. */
    public static final ApiDeprecations CURRENT = new ApiDeprecations(List.of(), Map.of());

    private final List<PathAlias> paths;
    private final Map<String, String> queryParameters;

    /**
     * A table of deprecated forms.
     *
     * @param paths path aliases, the more specific first: the first that matches wins
     * @param queryParameters snake_case query parameters and the camelCase names that replaced them
     */
    public ApiDeprecations(List<PathAlias> paths, Map<String, String> queryParameters) {
        this.paths = List.copyOf(paths);
        this.queryParameters = Map.copyOf(queryParameters);
    }

    /** snake_case query parameters and the camelCase names that replaced them. */
    public Map<String, String> queryParameters() {
        return queryParameters;
    }

    /** The query parameters of a request with legacy names replaced by the current ones; a current name wins. */
    public <V> Map<String, V> currentNames(Map<String, V> parameters) {
        Map<String, V> renamed = new LinkedHashMap<>();
        parameters.forEach((name, value) -> {
            String current = queryParameters.get(name);
            if (current == null) {
                renamed.put(name, value);
            } else if (!parameters.containsKey(current)) {
                renamed.put(current, value);
            }
        });
        return renamed;
    }

    /**
     * The successor of a deprecated request form, or empty for a current one. Works on request paths and on path
     * templates of the API description alike: a template variable is carried over as it is.
     */
    public Optional<Successor> successor(String method, String path) {
        if (paths.isEmpty()) {
            return Optional.empty();
        }
        PathContainer container = PathContainer.parsePath(path);
        for (PathAlias alias : paths) {
            if (!ANY.equals(alias.method()) && !alias.method().equalsIgnoreCase(method)) {
                continue;
            }
            PathPattern.PathMatchInfo match = alias.legacy().matchAndExtract(container);
            if (match != null) {
                String successorMethod = ANY.equals(alias.successorMethod()) ? method : alias.successorMethod();
                return Optional.of(new Successor(
                        alias.legacy().getPatternString(),
                        successorMethod,
                        expand(alias.successor(), match.getUriVariables())));
            }
        }
        return Optional.empty();
    }

    private static String expand(String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    expanded,
                    // A variable the legacy form does not carry in its path (one sent in the body) stays a template
                    // variable.
                    Matcher.quoteReplacement(variables.getOrDefault(matcher.group(1), matcher.group())));
        }
        return matcher.appendTail(expanded).toString();
    }

    /**
     * A path alias: {@code method} (or {@link #ANY}) on the {@code legacy} pattern is answered as {@code
     * successorMethod} (or the same method for {@link #ANY}) on {@code successor}, whose variables are taken from the
     * legacy path.
     */
    public static PathAlias alias(String method, String legacy, String successorMethod, String successor) {
        return new PathAlias(method, PathPatternParser.defaultInstance.parse(legacy), successorMethod, successor);
    }

    /** One deprecated path form; built with {@link #alias}. */
    public record PathAlias(String method, PathPattern legacy, String successorMethod, String successor) {}

    /**
     * The form to use instead: {@code alias} is the legacy pattern (a bounded metric tag), {@code method} and {@code
     * path} the successor request.
     */
    public record Successor(String alias, String method, String path) {}
}
