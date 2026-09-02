package com.greenwhite.dwh.instance.config.idempotency;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.error.ProblemDetailRecord;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String HEADER_IDEMPOTENT_REPLAY = "Idempotent-Replay";

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod().toUpperCase();
        String keyHeader = request.getHeader(HEADER_IDEMPOTENCY_KEY);

        // If not a mutating method or no Idempotency-Key header, continue standard chain
        if (keyHeader == null || keyHeader.isBlank() || !MUTATING_METHODS.contains(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Validate UUID format
        UUID idempotencyKey;
        try {
            idempotencyKey = UUID.fromString(keyHeader.trim());
        } catch (IllegalArgumentException ex) {
            writeProblemDetail(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "Некорректный формат Idempotency-Key. Ожидается валидный UUID.", request.getRequestURI());
            return;
        }

        // Read and cache request body
        byte[] requestBody = request.getInputStream().readAllBytes();
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request, requestBody);

        String requestHash = idempotencyService.computeRequestHash(
                method, request.getRequestURI(), request.getQueryString(), requestBody
        );

        // Check if key already processed
        var existingOpt = idempotencyService.findByKey(idempotencyKey);
        if (existingOpt.isPresent()) {
            var existing = existingOpt.get();

            // Check if payload matches
            if (existing.requestHash().equals(requestHash)) {
                // Replay cached response
                response.setStatus(existing.responseStatus());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader(HEADER_IDEMPOTENT_REPLAY, "true");
                response.getOutputStream().write(existing.responseBody().getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().flush();
                return;
            } else {
                // Payload mismatch -> 409 Conflict
                writeProblemDetail(response, HttpServletResponse.SC_CONFLICT, ErrorCode.IDEMPOTENCY_KEY_PAYLOAD_MISMATCH,
                        "Тело или параметры запроса не совпадают с исходным запросом для данного Idempotency-Key.",
                        request.getRequestURI());
                return;
            }
        }

        // Key is new -> process request and cache response
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(wrappedRequest, responseWrapper);
        } finally {
            int status = responseWrapper.getStatus();
            byte[] responseBytes = responseWrapper.getContentAsByteArray();

            // Cache successful or client-side responses (2xx, 3xx, 4xx) but not 5xx internal server errors
            if (status >= 200 && status < 500) {
                Long userId = SecurityContext.getCurrentUserId();
                String responseBodyStr = new String(responseBytes, StandardCharsets.UTF_8);
                idempotencyService.save(idempotencyKey, userId, requestHash, status, responseBodyStr);
            }

            responseWrapper.copyBodyToResponse();
        }
    }

    private void writeProblemDetail(HttpServletResponse response, int status, ErrorCode errorCode,
                                    String detail, String instance) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetailRecord problem = ProblemDetailRecord.of(errorCode, detail, instance);
        response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
        response.getOutputStream().flush();
    }
}
