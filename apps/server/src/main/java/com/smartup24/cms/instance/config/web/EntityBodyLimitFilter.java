package com.smartup24.cms.instance.config.web;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.ProblemDetailRecord;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.config.error.ProblemMessages;
import com.smartup24.cms.instance.config.idempotency.CachedBodyHttpServletRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The body of a change under {@code /api/v1/entities/} is at most {@value #MAX_BODY_BYTES} bytes (ADR-0032, 6.2 and 12,
 * "denial of service"): a larger one is refused with 413 before it is read into memory or parsed, whether it states
 * its length or comes in chunks.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class EntityBodyLimitFilter extends OncePerRequestFilter {

    /** The largest body of an entity change, the same limit the idempotency filter keeps for these paths. */
    public static final int MAX_BODY_BYTES = 512 * 1024;

    public static final String PREFIX = "/api/v1/entities/";

    private static final Set<String> CHANGES = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ObjectMapper mapper;
    private final ProblemMessages messages;

    public EntityBodyLimitFilter(ObjectMapper mapper, ObjectProvider<ProblemMessages> messages) {
        this.mapper = mapper;
        this.messages = messages.getIfAvailable(PackagedProblemMessages::new);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(PREFIX) || !CHANGES.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            refuse(request, response);
            return;
        }
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            refuse(request, response);
            return;
        }
        chain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String key = "error.payload_too_large";
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetailRecord problem = ProblemDetailRecord.of(
                ErrorCode.PAYLOAD_TOO_LARGE,
                key,
                null,
                messages.render(request, key, Map.of()),
                request.getRequestURI());
        response.getOutputStream().write(mapper.writeValueAsBytes(problem));
    }
}
