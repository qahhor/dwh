package com.greenwhite.dwh.common.filter;

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

/**
 * Стандартный W3C Traceparent фильтр для сквозной трассировки и логирования (ADR-0006).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class W3cTraceparentFilter extends OncePerRequestFilter {

    public static final String HEADER_TRACEPARENT = "traceparent";
    public static final String HEADER_CLIENT_CODE = "X-Client-Code";

    public static final String MDC_TRACEPARENT = "traceparent";
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_CLIENT_CODE = "client_code";

    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String incomingTraceparent = request.getHeader(HEADER_TRACEPARENT);
        String traceparent = (incomingTraceparent != null && isValidTraceparent(incomingTraceparent))
                ? incomingTraceparent.trim()
                : generateTraceparent();

        String traceId = extractTraceId(traceparent);

        String clientCode = request.getHeader(HEADER_CLIENT_CODE);
        if (clientCode == null || clientCode.isBlank()) {
            clientCode = "default";
        }

        MDC.put(MDC_TRACEPARENT, traceparent);
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_CLIENT_CODE, clientCode.trim());

        response.setHeader(HEADER_TRACEPARENT, traceparent);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACEPARENT);
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_CLIENT_CODE);
        }
    }

    private String generateTraceparent() {
        byte[] traceBytes = new byte[16];
        byte[] spanBytes = new byte[8];
        random.nextBytes(traceBytes);
        random.nextBytes(spanBytes);

        String traceId = HexFormat.of().formatHex(traceBytes);
        String spanId = HexFormat.of().formatHex(spanBytes);

        return "00-" + traceId + "-" + spanId + "-01";
    }

    public static boolean isValidTraceparent(String tp) {
        if (tp == null || tp.length() < 55) return false;
        String[] parts = tp.trim().split("-");
        return parts.length == 4
                && "00".equals(parts[0])
                && parts[1].length() == 32
                && parts[2].length() == 16
                && parts[3].length() == 2;
    }

    public static String extractTraceId(String tp) {
        if (tp == null) return "00000000000000000000000000000000";
        String[] parts = tp.trim().split("-");
        if (parts.length >= 2 && parts[1].length() == 32) {
            return parts[1];
        }
        return "00000000000000000000000000000000";
    }
}
