package com.smartup24.cms.instance.config.idempotency;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.ProblemDetailRecord;
import com.smartup24.cms.instance.config.error.ProblemMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/** What the idempotency filter writes itself: its refusals as problem details, and a stored answer replayed. */
final class IdempotencyAnswers {

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

    static boolean carriesBody(int status) {
        return status >= 200 && status != 204 && status != 205 && status != 304;
    }
}
