package com.smartup24.cms.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The gate of the incoming W3C trace context (plan 10/10, item 7.1; ADR-0009).
 *
 * <p>The trace itself is started by the HTTP observation of Spring Boot, which reads {@code traceparent}. This filter
 * runs before it and lets through only a header of the strict W3C form: version {@code 00}, a 32-hex trace id and a
 * 16-hex parent id that are not all zeros, 2-hex flags, lower case, nothing else. Anything else is hidden from the
 * rest of the chain together with its {@code tracestate}, so the request starts a new trace and the bad value is
 * never echoed back or written to a log.
 */
public class W3cTraceparentFilter extends OncePerRequestFilter {

    public static final String HEADER_TRACEPARENT = "traceparent";
    public static final String HEADER_TRACESTATE = "tracestate";

    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_PARENT_ID = "0".repeat(16);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(HEADER_TRACEPARENT));
        boolean accepted = values.isEmpty() || (values.size() == 1 && isValidTraceparent(values.getFirst()));
        filterChain.doFilter(accepted ? request : new WithoutTraceContext(request), response);
    }

    /** True for a {@code traceparent} of the strict W3C version 00 form. */
    public static boolean isValidTraceparent(String value) {
        if (value == null) {
            return false;
        }
        var matcher = TRACEPARENT.matcher(value);
        return matcher.matches() && !ZERO_TRACE_ID.equals(matcher.group(1)) && !ZERO_PARENT_ID.equals(matcher.group(2));
    }

    /** The request with the trace context headers removed. */
    private static final class WithoutTraceContext extends HttpServletRequestWrapper {

        WithoutTraceContext(HttpServletRequest request) {
            super(request);
        }

        private static boolean hidden(String name) {
            return HEADER_TRACEPARENT.equalsIgnoreCase(name) || HEADER_TRACESTATE.equalsIgnoreCase(name);
        }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(WithoutTraceContext::hidden);
            return Collections.enumeration(names);
        }
    }
}
