package com.smartup24.cms.instance.config.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Returns the trace of the request in the {@code traceparent} response header (plan 10/10, items 7.1 and 7.2), so a
 * support request can name the trace id that its log lines carry. Runs right after the HTTP observation of Spring Boot
 * (order {@code HIGHEST_PRECEDENCE + 1}) has started the server span; the header names that span, never an incoming
 * value as such.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceResponseHeaderFilter extends OncePerRequestFilter {

    static final String HEADER = "traceparent";

    private final ObjectProvider<Tracer> tracer;

    public TraceResponseHeaderFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceparent = traceparent(tracer.getIfAvailable());
        if (traceparent != null) {
            response.setHeader(HEADER, traceparent);
        }
        filterChain.doFilter(request, response);
    }

    /** The W3C {@code traceparent} of the current span, or null outside a trace. */
    static @Nullable String traceparent(@Nullable Tracer tracer) {
        Span span = tracer == null ? null : tracer.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        if (context.traceId().isEmpty() || context.spanId().isEmpty()) {
            return null;
        }
        return "00-" + context.traceId() + "-" + context.spanId()
                + (Boolean.TRUE.equals(context.sampled()) ? "-01" : "-00");
    }
}
