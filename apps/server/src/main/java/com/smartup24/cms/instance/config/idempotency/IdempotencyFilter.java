package com.smartup24.cms.instance.config.idempotency;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.ProblemDetailRecord;
import com.smartup24.cms.instance.common.annotation.ReturnsSecret;
import com.smartup24.cms.instance.common.security.SecurityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
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

    public static final int MAX_REQUEST_BODY_BYTES = 65_536; // 64 KB
    public static final int MAX_RESPONSE_BODY_BYTES = 65_536; // 64 KB

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    /** Finds the handler of a request, to honour {@link ReturnsSecret}; absent in slice tests. */
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

    @Autowired
    public IdempotencyFilter(IdempotencyService idempotencyService, ObjectMapper objectMapper,
                             @Qualifier("requestMappingHandlerMapping")
                             ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.handlerMapping = handlerMapping;
    }

    public IdempotencyFilter(IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this(idempotencyService, objectMapper, null);
    }

    private boolean isUnsupportedPath(String uri) {
        return uri.startsWith("/api/v1/auth/")
                || uri.equals("/api/v1/auth")
                || uri.startsWith("/api/v1/iam/profile/channels");
    }

    /**
     * Whether the handler returns a secret ({@link ReturnsSecret}). When the handler cannot be resolved the
     * answer is yes: a response that is not stored costs a replay, a stored secret costs a leak.
     */
    private boolean returnsSecret(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = handlerMapping == null ? null : handlerMapping.getIfAvailable();
        if (mapping == null) {
            return false;
        }
        try {
            HandlerExecutionChain chain = mapping.getHandler(request);
            return chain != null && chain.getHandler() instanceof HandlerMethod method
                    && method.hasMethodAnnotation(ReturnsSecret.class);
        } catch (Exception e) {
            log.warn("idempotency_handler_lookup_failed uri={}", request.getRequestURI(), e);
            return true;
        }
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

        // 1. Validate sensitive / unsupported endpoint
        if (isUnsupportedPath(request.getRequestURI())) {
            writeProblemDetail(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.IDEMPOTENCY_NOT_SUPPORTED,
                    "Идемпотентность не поддерживается для эндпоинтов авторизации и генерации секретов.",
                    request.getRequestURI());
            return;
        }

        // A response carrying a secret is never stored for replay: run it without a reservation.
        if (returnsSecret(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Validate multipart and content-length header
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            writeProblemDetail(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.IDEMPOTENCY_NOT_SUPPORTED,
                    "Идемпотентность не поддерживается для multipart-загрузок файлов.",
                    request.getRequestURI());
            return;
        }

        int contentLength = request.getContentLength();
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            writeProblemDetail(response, 413, ErrorCode.PAYLOAD_TOO_LARGE,
                    "Размер тела запроса с Idempotency-Key превышает допустимый лимит (64 КБ).",
                    request.getRequestURI());
            return;
        }

        // 3. Validate UUID format
        UUID idempotencyKey;
        try {
            idempotencyKey = UUID.fromString(keyHeader.trim());
        } catch (IllegalArgumentException ex) {
            writeProblemDetail(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "Некорректный формат Idempotency-Key. Ожидается валидный UUID.", request.getRequestURI());
            return;
        }

        // 4. Read and cache request body up to limit + 1 byte (handles chunked/unknown content-length safely)
        byte[] requestBody = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (requestBody.length > MAX_REQUEST_BODY_BYTES) {
            writeProblemDetail(response, 413, ErrorCode.PAYLOAD_TOO_LARGE,
                    "Размер тела запроса с Idempotency-Key превышает допустимый лимит (64 КБ).",
                    request.getRequestURI());
            return;
        }
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request, requestBody);

        String requestHash = idempotencyService.computeRequestHash(
                method, request.getRequestURI(), request.getQueryString(), requestBody
        );

        Long userId = SecurityContext.getCurrentUserId();
        IdempotencyService.Claim claim = idempotencyService.claim(idempotencyKey, userId, requestHash);
        switch (claim.state()) {
            case REPLAY -> {
                var existing = claim.existing();
                response.setStatus(existing.responseStatus());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader(HEADER_IDEMPOTENT_REPLAY, "true");
                response.getOutputStream().write(existing.responseBody().getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().flush();
                return;
            }
            case PAYLOAD_MISMATCH -> {
                writeProblemDetail(response, HttpServletResponse.SC_CONFLICT, ErrorCode.IDEMPOTENCY_KEY_PAYLOAD_MISMATCH,
                        "Тело или параметры запроса не совпадают с исходным запросом для данного Idempotency-Key.",
                        request.getRequestURI());
                return;
            }
            case IN_PROGRESS -> {
                writeProblemDetail(response, HttpServletResponse.SC_CONFLICT, ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                        "Запрос с данным Idempotency-Key уже выполняется. Повторите запрос позднее.",
                        request.getRequestURI());
                return;
            }
            case ACQUIRED -> {
                // Continue below: this request owns the database reservation.
            }
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        boolean chainCompleted = false;
        try {
            filterChain.doFilter(wrappedRequest, responseWrapper);
            chainCompleted = true;
        } finally {
            int status = responseWrapper.getStatus();
            byte[] responseBytes = responseWrapper.getContentAsByteArray();

            try {
                // Cache successful and client-side responses if within safe body size. Exceptions and 5xx
                // release the reservation so a corrected retry can execute.
                if (chainCompleted && status >= 200 && status < 500) {
                    if (responseBytes.length <= MAX_RESPONSE_BODY_BYTES) {
                        String responseBodyStr = new String(responseBytes, StandardCharsets.UTF_8);
                        idempotencyService.complete(
                                idempotencyKey, claim.reservationToken(), status, responseBodyStr);
                    } else {
                        // Oversized response: release reservation so huge payload is not stored in DB
                        idempotencyService.release(idempotencyKey, claim.reservationToken());
                    }
                } else {
                    idempotencyService.release(idempotencyKey, claim.reservationToken());
                }
            } finally {
                responseWrapper.copyBodyToResponse();
            }
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
