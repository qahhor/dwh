package com.smartup24.cms.instance.config.security;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.ProblemDetailRecord;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.config.error.ProblemMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 401/403 из security-цепочки в формате RFC 9457 (FR-API-2) —
 * тем же контрактом, что и GlobalExceptionHandler на уровне MVC.
 */
@Component
public class ProblemDetailAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProblemDetailAuthHandlers.class);

    private final ObjectMapper objectMapper;
    private final ProblemMessages messages;

    /** A context without the i18n service (a web slice) renders from the packaged catalogs. */
    @Autowired
    public ProblemDetailAuthHandlers(ObjectMapper objectMapper, ObjectProvider<ProblemMessages> messages) {
        this(objectMapper, messages.getIfAvailable(PackagedProblemMessages::new));
    }

    public ProblemDetailAuthHandlers(ObjectMapper objectMapper, ProblemMessages messages) {
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        if (response.isCommitted()) {
            log.debug("Authentication failure after response commit on {}", request.getRequestURI());
            return;
        }
        writeProblem(request, response, ErrorCode.UNAUTHORIZED, "error.authentication_required", Map.of());
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        if (response.isCommitted()) {
            log.debug("Access denied after response commit on {}", request.getRequestURI());
            return;
        }
        ErrorCode code =
                accessDeniedException instanceof CsrfException ? ErrorCode.CSRF_TOKEN_INVALID : ErrorCode.FORBIDDEN;
        log.warn(
                "AccessDenied [code={}] on {} | exceptionType={}, csrfHeaderPresent={}, cookieNames={}",
                code,
                request.getRequestURI(),
                accessDeniedException.getClass().getSimpleName(),
                request.getHeader("X-XSRF-TOKEN") != null,
                request.getCookies() != null
                        ? Arrays.stream(request.getCookies())
                                .map(jakarta.servlet.http.Cookie::getName)
                                .toList()
                        : List.of());
        writeProblem(request, response, code, ApiException.defaultKey(code), Map.of());
    }

    /** Writes the problem of {@code code} with the text of {@code key}, in the request's language. */
    public void writeProblem(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode code,
            String key,
            Map<String, Object> params)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String uri = request.getRequestURI();
        ProblemDetailRecord problem =
                ProblemDetailRecord.of(code, key, params, messages.render(request, key, params), uri);
        response.setStatus(code.getDefaultStatus());
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
