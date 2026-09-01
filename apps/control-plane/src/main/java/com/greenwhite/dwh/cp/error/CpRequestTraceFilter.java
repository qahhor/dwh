package com.greenwhite.dwh.cp.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CpRequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    public static final String MDC_TRACE_ID = "traceId";

    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_SPAN_ID = "0000000000000000";
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^(?!ff)[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");

    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = incomingTraceId(request.getHeader(TRACEPARENT_HEADER));
        if (traceId == null) {
            traceId = newTraceId();
        }

        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(MDC_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    private static String incomingTraceId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
        if (!matcher.matches()) {
            return null;
        }
        String traceId = matcher.group(1);
        String spanId = matcher.group(2);
        if (ZERO_TRACE_ID.equals(traceId) || ZERO_SPAN_ID.equals(spanId)) {
            return null;
        }
        return traceId;
    }

    private String newTraceId() {
        String traceId;
        do {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            traceId = HexFormat.of().formatHex(bytes);
        } while (ZERO_TRACE_ID.equals(traceId));
        return traceId;
    }
}
