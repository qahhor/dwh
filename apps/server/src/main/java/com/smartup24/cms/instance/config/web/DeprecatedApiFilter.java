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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers a deprecated request form (plan 10/10, item 3.4, ADR-0023; the forms are listed in
 * {@link ApiDeprecations}) as before, and says so: {@code Deprecation} (RFC 9745), {@code Sunset} (RFC 8594) and,
 * for a path, {@code Link: <successor>; rel="successor-version"}. A snake_case query parameter reaches the handler
 * under its camelCase name. {@code dwh_api_deprecated_calls_total} counts the calls, so the alias is removed when
 * nobody uses it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DeprecatedApiFilter extends OncePerRequestFilter {

    static final String DEPRECATION = "Deprecation";
    static final String SUNSET = "Sunset";
    static final String LINK = "Link";

    private static final String DEPRECATION_VALUE =
            "@" + ApiDeprecations.DEPRECATED_SINCE.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    private static final String SUNSET_VALUE =
            DateTimeFormatter.RFC_1123_DATE_TIME.format(ApiDeprecations.SUNSET.atStartOfDay(ZoneOffset.UTC));

    /** Absent in slices without metrics; the headers do not depend on it. */
    private final ObjectProvider<MeterRegistry> meters;

    public DeprecatedApiFilter(ObjectProvider<MeterRegistry> meters) {
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Optional<ApiDeprecations.Successor> successor = ApiDeprecations.successor(request.getMethod(), path);
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
        meters.ifAvailable(registry -> Counter.builder("dwh_api_deprecated_calls_total")
                .description("Calls of API forms deprecated for one release (ADR-0023)")
                .tag("alias", alias)
                .register(registry)
                .increment());
    }

    /** The parameters with legacy names replaced, or {@code null} when the request uses none. */
    private static @Nullable Map<String, String[]> renamedParameters(HttpServletRequest request) {
        // Only the query string is looked at: reading the parameters of a form or multipart body here would consume
        // the body before the idempotency filter and the upload handlers see it.
        String query = request.getQueryString();
        if (query == null
                || Arrays.stream(query.split("&"))
                        .map(pair -> URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8))
                        .noneMatch(ApiDeprecations.QUERY_PARAMETERS::containsKey)) {
            return null;
        }
        Map<String, String[]> renamed = ApiDeprecations.currentNames(request.getParameterMap());
        return Collections.unmodifiableMap(renamed);
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
