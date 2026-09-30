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
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
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

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyService idempotencyService;
    private final IdempotencyAnswers answers;
    /** Finds the handler of a request, to honour {@link ReturnsSecret}; absent in slice tests. */
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;
    /**
     * Runs an accepted request and records its answer in one transaction (plan 10/10, item 3.12); absent in slice
     * tests, where the answer is recorded after the request as before.
     */
    private final @Nullable PlatformTransactionManager transactions;

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
        this.transactions = transactions;
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
        byte[] requestBody = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (requestBody.length > MAX_REQUEST_BODY_BYTES) {
            answers.problem(request, response, 413, ErrorCode.PAYLOAD_TOO_LARGE, "error.idempotency_body_too_large");
            return;
        }
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request, requestBody);

        String requestHash = idempotencyService.computeRequestHash(
                method, request.getRequestURI(), request.getQueryString(), requestBody);

        Long userId = SecurityContext.getCurrentUserId();
        IdempotencyService.Claim claim = idempotencyService.claim(idempotencyKey, userId, requestHash);
        if (answeredByClaim(claim, request, response)) {
            return;
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        if (transactions == null) {
            runAndRecordAfter(wrappedRequest, responseWrapper, filterChain, idempotencyKey, claim.reservationToken());
        } else {
            runAndRecordAtomically(
                    transactions,
                    wrappedRequest,
                    responseWrapper,
                    filterChain,
                    idempotencyKey,
                    claim.reservationToken());
        }
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
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            answers.problem(request, response, 413, ErrorCode.PAYLOAD_TOO_LARGE, "error.idempotency_body_too_large");
            return true;
        }

        return false;
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

    /**
     * Answers a request whose key is already taken: the stored answer is replayed, another payload or a request still
     * running is refused. False when this request owns the reservation and must run.
     */
    private boolean answeredByClaim(
            IdempotencyService.Claim claim, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        switch (claim.state()) {
            case REPLAY -> {
                answers.replay(response, claim.existing());
                return true;
            }
            case PAYLOAD_MISMATCH -> {
                answers.problem(
                        request,
                        response,
                        HttpServletResponse.SC_CONFLICT,
                        ErrorCode.IDEMPOTENCY_KEY_PAYLOAD_MISMATCH,
                        "error.idempotency_key_payload_mismatch");
                return true;
            }
            case IN_PROGRESS -> {
                answers.problem(
                        request,
                        response,
                        HttpServletResponse.SC_CONFLICT,
                        ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                        "error.idempotency_request_in_progress");
                return true;
            }
            case ACQUIRED -> {
                return false;
            }
        }

        return false;
    }

    /**
     * Plan 10/10, item 3.12: the request and the record of its answer commit together. The business writes join the
     * transaction opened here (propagation REQUIRED), and the answer is stored in it before the commit, so a process
     * that dies after the commit leaves a stored answer for the retry instead of running the operation twice.
     * A 5xx rolls the request back and frees the key, so a corrected retry runs again. When the business code has
     * rolled back (a refusal thrown from a transactional method), nothing of the request is committed and only the
     * answer is recorded. The answer reaches the client only after the commit.
     */
    private void runAndRecordAtomically(
            PlatformTransactionManager transactionManager,
            HttpServletRequest request,
            ContentCachingResponseWrapper responseWrapper,
            FilterChain filterChain,
            UUID key,
            UUID reservationToken)
            throws ServletException, IOException {
        var definition = new DefaultTransactionDefinition();
        definition.setName("idempotent-request");
        TransactionStatus tx = transactionManager.getTransaction(definition);
        try {
            filterChain.doFilter(request, responseWrapper);
        } catch (IOException | ServletException | RuntimeException e) {
            transactionManager.rollback(tx);
            idempotencyService.release(key, reservationToken);
            responseWrapper.copyBodyToResponse();
            throw e;
        }
        int status = responseWrapper.getStatus();
        byte[] body = responseWrapper.getContentAsByteArray();
        if (status < 500 && !tx.isRollbackOnly()) {
            try {
                // An answer that cannot be kept (too large, not JSON) still commits; only its replay is lost.
                record(key, reservationToken, responseWrapper, status, body);
                transactionManager.commit(tx);
            } catch (RuntimeException e) {
                // The commit failed after the handler answered: the operation did not happen, so the buffered
                // success must not reach the client.
                log.error("idempotent_commit_failed key={} uri={}", key, request.getRequestURI(), e);
                if (!tx.isCompleted()) {
                    transactionManager.rollback(tx);
                }
                idempotencyService.release(key, reservationToken);
                responseWrapper.resetBuffer();
                answers.problem(
                        request,
                        responseWrapper,
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        ErrorCode.INTERNAL_ERROR,
                        "error.internal_error");
            }
        } else if (status < 400) {
            // The handler reports success although something in the request rolled back (a failure swallowed on
            // the way): nothing was written, so a success must not reach the client or be replayed.
            log.error("idempotent_success_rolled_back key={} uri={} status={}", key, request.getRequestURI(), status);
            transactionManager.rollback(tx);
            idempotencyService.release(key, reservationToken);
            responseWrapper.resetBuffer();
            answers.problem(
                    request,
                    responseWrapper,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    ErrorCode.INTERNAL_ERROR,
                    "error.internal_error");
        } else {
            transactionManager.rollback(tx);
            record(key, reservationToken, responseWrapper, status, body);
        }
        responseWrapper.copyBodyToResponse();
    }

    /** Stores the answer for a replay when it can be kept, otherwise frees the key. */
    private void record(UUID key, UUID reservationToken, HttpServletResponse response, int status, byte[] body) {
        if (storable(response, status, body)) {
            idempotencyService.complete(key, reservationToken, answer(response, status, body));
        } else {
            idempotencyService.release(key, reservationToken);
        }
    }

    /** Without a transaction manager (web slices): the answer is recorded after the request, as before 3.12. */
    private void runAndRecordAfter(
            HttpServletRequest request,
            ContentCachingResponseWrapper responseWrapper,
            FilterChain filterChain,
            UUID key,
            UUID reservationToken)
            throws ServletException, IOException {
        boolean chainCompleted = false;
        try {
            filterChain.doFilter(request, responseWrapper);
            chainCompleted = true;
        } finally {
            int status = responseWrapper.getStatus();
            byte[] responseBytes = responseWrapper.getContentAsByteArray();
            try {
                if (chainCompleted && storable(responseWrapper, status, responseBytes)) {
                    idempotencyService.complete(key, reservationToken, answer(responseWrapper, status, responseBytes));
                } else {
                    idempotencyService.release(key, reservationToken);
                }
            } finally {
                responseWrapper.copyBodyToResponse();
            }
        }
    }

    /**
     * A final answer below 500 whose body fits and is JSON (the stored body is jsonb) or empty. Another answer is not
     * kept: the key is freed and a retry runs again.
     */
    private static boolean storable(HttpServletResponse response, int status, byte[] body) {
        if (status < 200 || status >= 500 || body.length > MAX_RESPONSE_BODY_BYTES) {
            return false;
        }
        String type = response.getContentType();
        return body.length == 0 || type == null || isJson(type);
    }

    private static boolean isJson(String contentType) {
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.includes(type) || type.getSubtype().endsWith("+json");
        } catch (InvalidMediaTypeException malformed) {
            log.warn("idempotency_content_type_unreadable type={}: {}", contentType, malformed.getMessage());
            return false;
        }
    }

    /** What a replay repeats: status, body, and the Location, Content-Type and ETag of the original answer. */
    private static IdempotencyRepository.StoredAnswer answer(HttpServletResponse response, int status, byte[] body) {
        return new IdempotencyRepository.StoredAnswer(
                status,
                new String(body, StandardCharsets.UTF_8),
                response.getHeader(HttpHeaders.LOCATION),
                body.length == 0 ? null : response.getContentType(),
                response.getHeader(HttpHeaders.ETAG));
    }
}
