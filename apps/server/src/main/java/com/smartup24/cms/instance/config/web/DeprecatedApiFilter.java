package com.smartup24.cms.instance.config.web;

import com.smartup24.cms.instance.common.web.ApiDeprecations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers a deprecated request form (plan 10/10, item 3.4, ADR-0023; the forms are listed in
 * {@link ApiDeprecations#CURRENT}) as before, and says so: {@code Deprecation} (RFC 9745), {@code Sunset} (RFC 8594)
 * and, for a path, {@code Link: <successor>; rel="successor-version"}. A snake_case query parameter reaches the
 * handler under its camelCase name. {@code smc_api_deprecated_calls_total} counts the calls, so the alias is removed
 * when nobody uses it. With an empty table every request passes through untouched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DeprecatedApiFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DeprecatedApiFilter.class);

    static final String DEPRECATION = "Deprecation";
    static final String SUNSET = "Sunset";
    static final String LINK = "Link";

    private static final String DEPRECATION_VALUE =
            "@" + ApiDeprecations.DEPRECATED_SINCE.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    private static final String SUNSET_VALUE =
            DateTimeFormatter.RFC_1123_DATE_TIME.format(ApiDeprecations.SUNSET.atStartOfDay(ZoneOffset.UTC));

    /** Absent in slices without metrics; the headers do not depend on it. */
    private final ObjectProvider<MeterRegistry> meters;

    private final ApiDeprecations deprecations;

    @Autowired
    public DeprecatedApiFilter(ObjectProvider<MeterRegistry> meters) {
        this(meters, ApiDeprecations.CURRENT);
    }

    /** A filter over another table of deprecated forms: the tests use a fixture while {@code CURRENT} is empty. */
    DeprecatedApiFilter(ObjectProvider<MeterRegistry> meters, ApiDeprecations deprecations) {
        this.meters = meters;
        this.deprecations = deprecations;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Optional<ApiDeprecations.Successor> successor = deprecations.successor(request.getMethod(), path);
        successor.ifPresent(found -> {
            announce(response, found.alias());
            if (!found.path().contains("{")) {
                response.addHeader(
                        LINK, "<" + request.getContextPath() + found.path() + ">; rel=\"successor-version\"");
            }
        });

        Map<String, String[]> renamed = renamedParameters(request);
        if (renamed == null) {
            chain.doFilter(request, response);
            return;
        }
        if (successor.isEmpty()) {
            announce(response, "query-parameters");
        }
        chain.doFilter(new RenamedParameters(request, renamed), response);
    }

    private void announce(HttpServletResponse response, String alias) {
        response.setHeader(DEPRECATION, DEPRECATION_VALUE);
        response.setHeader(SUNSET, SUNSET_VALUE);
        meters.ifAvailable(registry -> Counter.builder("smc_api_deprecated_calls_total")
                .description("Calls of API forms deprecated for one release (ADR-0023)")
                .tag("alias", alias)
                .register(registry)
                .increment());
    }

    /** The parameters with legacy names replaced, or {@code null} when the request uses none. */
    private @Nullable Map<String, String[]> renamedParameters(HttpServletRequest request) {
        // Only the query string is looked at: reading the parameters of a form or multipart body here would consume
        // the body before the idempotency filter and the upload handlers see it.
        String query = request.getQueryString();
        Map<String, String> legacyNames = deprecations.queryParameters();
        if (query == null
                || legacyNames.isEmpty()
                || Arrays.stream(query.split("&"))
                        .map(pair -> decodedName(pair.split("=", 2)[0]))
                        .noneMatch(legacyNames::containsKey)) {
            return null;
        }
        Map<String, String[]> renamed = deprecations.currentNames(request.getParameterMap());
        return Collections.unmodifiableMap(renamed);
    }

    /**
     * The decoded name of a query parameter. A malformed escape ({@code %zz}) is not a legacy name: the request goes
     * on untouched and the handler answers it, instead of this filter failing before security with a container 500.
     */
    static String decodedName(String name) {
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            log.debug("Query parameter name is not URL-encoded correctly: {}", malformed.getMessage());
            return name;
        }
    }

    private static final class RenamedParameters extends HttpServletRequestWrapper {

        private final Map<String, String[]> parameters;

        RenamedParameters(HttpServletRequest request, Map<String, String[]> parameters) {
            super(request);
            this.parameters = parameters;
        }

        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            return parameters.get(name);
        }
    }
}
