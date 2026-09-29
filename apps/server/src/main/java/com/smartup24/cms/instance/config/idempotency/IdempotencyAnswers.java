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

    void replay(HttpServletResponse response, IdempotencyRepository.IdempotencyRecord stored) throws IOException {
        response.setStatus(stored.responseStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAY, "true");
        if (stored.responseLocation() != null) {
            response.setHeader(HttpHeaders.LOCATION, stored.responseLocation());
        }
        response.getOutputStream().write(stored.responseBody().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }
}
