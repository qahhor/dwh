package com.smartup24.cms.instance.config.idempotency;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.ReturnsSecret;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.config.error.ProblemMessages;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String HEADER_IDEMPOTENT_REPLAY = "Idempotent-Replay";

    public static final int MAX_REQUEST_BODY_BYTES = 65_536; // 64 KB
    public static final int MAX_RESPONSE_BODY_BYTES = 65_536; // 64 KB

    /**
     * The limit of a request and of a stored answer under {@code /api/v1/entities/} (ADR-0032, 6.2): a record with its
     * fields is larger than the other changes, and the entity body limit there is the same 512 KB.
     */
    public static final int MAX_ENTITY_BODY_BYTES = 524_288; // 512 KB

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyService idempotencyService;
    private final IdempotencyAnswers answers;
    /** Finds the handler of a request, to honour {@link ReturnsSecret}; absent in slice tests. */
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;
    /** Runs an accepted request and records its answer (plan 10/10, item 3.12). */
    private final IdempotentExecution execution;

    @Autowired
    public IdempotencyFilter(
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            ObjectProvider<ProblemMessages> messages,
            ObjectProvider<PlatformTransactionManager> transactions,
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this(
                idempotencyService,
                objectMapper,
                messages.getIfAvailable(PackagedProblemMessages::new),
                transactions.getIfUnique(),
                handlerMapping);
    }

    public IdempotencyFilter(
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            ProblemMessages messages,
            @Nullable PlatformTransactionManager transactions,
            ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this.idempotencyService = idempotencyService;
        this.answers = new IdempotencyAnswers(objectMapper, messages);
        this.execution = new IdempotentExecution(idempotencyService, answers, transactions);
        this.handlerMapping = handlerMapping;
    }

    public IdempotencyFilter(
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            ProblemMessages messages,
            ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this(idempotencyService, objectMapper, messages, null, handlerMapping);
    }

    public IdempotencyFilter(
            IdempotencyService idempotencyService, ObjectMapper objectMapper, ProblemMessages messages) {
        this(idempotencyService, objectMapper, messages, null, null);
    }

    /**
     * Whether a path takes an Idempotency-Key: sign-in and the delivery channels of the profile answer secrets and
     * refuse it (400). The API description declares the header where this is true.
     */
    public static boolean supportsPath(String uri) {
        return !uri.startsWith("/api/v1/auth/")
                && !uri.equals("/api/v1/auth")
                && !uri.startsWith("/api/v1/iam/profile/channels");
    }

    private boolean isUnsupportedPath(String uri) {
        return !supportsPath(uri);
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
            return chain != null
                    && chain.getHandler() instanceof HandlerMethod method
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
            answers.problem(
                    request,
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    ErrorCode.IDEMPOTENCY_NOT_SUPPORTED,
                    "error.idempotency_auth_unsupported");
            return;
        }

        // A response carrying a secret is never stored for replay: run it without a reservation.
        if (returnsSecret(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (refusedByShape(request, response)) {
            return;
        }

        UUID idempotencyKey = parseKey(request, response, keyHeader);
        if (idempotencyKey == null) {
            return;
        }

        // 4. Read and cache request body up to limit + 1 byte (handles chunked/unknown content-length safely)
        int limit = bodyLimit(request);
        byte[] requestBody = request.getInputStream().readNBytes(limit + 1);
        if (requestBody.length > limit) {
            answers.problem(request, response, 413, ErrorCode.PAYLOAD_TOO_LARGE, tooLargeKey(limit));
            return;
        }
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request, requestBody);

        String requestHash = idempotencyService.computeRequestHash(
                method, request.getRequestURI(), request.getQueryString(), requestBody);

        Long userId = SecurityContext.getCurrentUserId();
        IdempotencyService.Claim claim = idempotencyService.claim(idempotencyKey, userId, requestHash);
        if (answers.answeredByClaim(claim, request, response)) {
            return;
        }

        execution.run(
                wrappedRequest,
                new ContentCachingResponseWrapper(response),
                filterChain,
                idempotencyKey,
                claim.reservationToken());
    }

    /** Multipart bodies and bodies over the limit are not kept for replay: refused before the body is read. */
    private boolean refusedByShape(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            answers.problem(
                    request,
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    ErrorCode.IDEMPOTENCY_NOT_SUPPORTED,
                    "error.idempotency_multipart_unsupported");
            return true;
        }

        int contentLength = request.getContentLength();
        int limit = bodyLimit(request);
        if (contentLength > limit) {
            answers.problem(request, response, 413, ErrorCode.PAYLOAD_TOO_LARGE, tooLargeKey(limit));
            return true;
        }

        return false;
    }

    private static int bodyLimit(HttpServletRequest request) {
        return IdempotencyAnswers.bodyLimit(request);
    }

    private static String tooLargeKey(int limit) {
        return IdempotencyAnswers.tooLargeKey(limit);
    }

    /** The key as a UUID, or null after answering 400 for a key of another shape. */
    private @Nullable UUID parseKey(HttpServletRequest request, HttpServletResponse response, String keyHeader)
            throws IOException {
        try {
            return UUID.fromString(keyHeader.trim());
        } catch (IllegalArgumentException ex) {
            answers.problem(
                    request,
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "error.idempotency_key_format");
            return null;
        }
    }
}
