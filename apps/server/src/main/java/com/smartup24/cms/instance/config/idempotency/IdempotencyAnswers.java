package com.smartup24.cms.instance.config.idempotency;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.ProblemDetailRecord;
import com.smartup24.cms.instance.config.error.ProblemMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/** What the idempotency filter writes itself: its refusals as problem details, and a stored answer replayed. */
final class IdempotencyAnswers {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAnswers.class);

    private final ObjectMapper objectMapper;
    private final ProblemMessages messages;

    IdempotencyAnswers(ObjectMapper objectMapper, ProblemMessages messages) {
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    void problem(HttpServletRequest request, HttpServletResponse response, int status, ErrorCode code, String key)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetailRecord problem = ProblemDetailRecord.of(
                code, key, null, messages.render(request, key, Map.of()), request.getRequestURI());
        response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
        response.getOutputStream().flush();
    }

    /**
     * Answers a request whose key is already taken: the stored answer is replayed, another payload or a request still
     * running is refused. False when this request owns the reservation and must run.
     */
    boolean answeredByClaim(IdempotencyService.Claim claim, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        switch (claim.state()) {
            case REPLAY -> {
                replay(response, claim.existing());
                return true;
            }
            case PAYLOAD_MISMATCH -> {
                problem(
                        request,
                        response,
                        HttpServletResponse.SC_CONFLICT,
                        ErrorCode.IDEMPOTENCY_KEY_PAYLOAD_MISMATCH,
                        "error.idempotency_key_payload_mismatch");
                return true;
            }
            case IN_PROGRESS -> {
                problem(
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
     * The stored answer as it went out the first time: status, Location, ETag, Content-Type and body. An answer that
     * has no body (204, 205, 304) is replayed without one; a row stored before its Content-Type was kept is JSON.
     */
    void replay(HttpServletResponse response, IdempotencyRepository.IdempotencyRecord stored) throws IOException {
        int status = stored.responseStatus();
        response.setStatus(status);
        response.setHeader(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAY, "true");
        if (stored.responseLocation() != null) {
            response.setHeader(HttpHeaders.LOCATION, stored.responseLocation());
        }
        if (stored.responseEtag() != null) {
            response.setHeader(HttpHeaders.ETAG, stored.responseEtag());
        }
        if (!carriesBody(status)) {
            response.flushBuffer();
            return;
        }
        response.setContentType(
                stored.responseContentType() != null ? stored.responseContentType() : MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(stored.responseBody().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    /**
     * A final answer below 500 whose body fits and is JSON (the stored body is jsonb) or empty. Another answer is not
     * kept: the key is freed and a retry runs again.
     */
    static boolean storable(HttpServletResponse response, int status, byte[] body, int maxBytes) {
        if (status < 200 || status >= 500 || body.length > maxBytes) {
            return false;
        }
        String type = response.getContentType();
        return body.length == 0 || type == null || isJson(type);
    }

    private static boolean isJson(String contentType) {
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.includes(type)
                    || type.getSubtype().endsWith("+json");
        } catch (InvalidMediaTypeException malformed) {
            log.warn("idempotency_content_type_unreadable type={}: {}", contentType, malformed.getMessage());
            return false;
        }
    }

    /** What a replay repeats: status, body, and the Location, Content-Type and ETag of the original answer. */
    static IdempotencyRepository.StoredAnswer answer(HttpServletResponse response, int status, byte[] body) {
        return new IdempotencyRepository.StoredAnswer(
                status,
                new String(body, StandardCharsets.UTF_8),
                response.getHeader(HttpHeaders.LOCATION),
                body.length == 0 ? null : response.getContentType(),
                response.getHeader(HttpHeaders.ETAG));
    }

    /** The largest request body kept for a replay: 512 KB for an entity record (ADR-0032, 6.2), 64 KB for the rest. */
    static int bodyLimit(HttpServletRequest request) {
        return entityPath(request) ? IdempotencyFilter.MAX_ENTITY_BODY_BYTES : IdempotencyFilter.MAX_REQUEST_BODY_BYTES;
    }

    /** The largest answer kept for a replay, by the same rule. */
    static int answerLimit(HttpServletRequest request) {
        return entityPath(request)
                ? IdempotencyFilter.MAX_ENTITY_BODY_BYTES
                : IdempotencyFilter.MAX_RESPONSE_BODY_BYTES;
    }

    /** The text of a refusal over the limit: each limit names its size. */
    static String tooLargeKey(int limit) {
        return limit == IdempotencyFilter.MAX_ENTITY_BODY_BYTES
                ? "error.idempotency.entity_body_too_large"
                : "error.idempotency_body_too_large";
    }

    private static boolean entityPath(HttpServletRequest request) {
        return request.getRequestURI()
                .substring(request.getContextPath().length())
                .startsWith("/api/v1/entities/");
    }

    static boolean carriesBody(int status) {
        return status >= 200 && status != 204 && status != 205 && status != 304;
    }
}
