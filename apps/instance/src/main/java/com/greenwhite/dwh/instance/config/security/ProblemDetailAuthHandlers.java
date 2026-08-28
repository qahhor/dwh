package com.greenwhite.dwh.instance.config.security;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.error.ProblemDetailRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 401/403 из security-цепочки в формате RFC 9457 (FR-API-2) —
 * тем же контрактом, что и GlobalExceptionHandler на уровне MVC.
 */
@Component
public class ProblemDetailAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writeProblem(response, ErrorCode.UNAUTHORIZED,
                "Требуется аутентификация для доступа к ресурсу", request.getRequestURI());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ErrorCode code = accessDeniedException instanceof CsrfException
                ? ErrorCode.CSRF_TOKEN_INVALID
                : ErrorCode.FORBIDDEN;
        String detail = code == ErrorCode.CSRF_TOKEN_INVALID
                ? "Отсутствует или недействителен CSRF-токен (заголовок X-XSRF-TOKEN)"
                : "Доступ запрещён";
        writeProblem(response, code, detail, request.getRequestURI());
    }

    public void writeProblem(HttpServletResponse response, ErrorCode code, String detail, String uri)
            throws IOException {
        ProblemDetailRecord problem = ProblemDetailRecord.of(code, detail, uri);
        response.setStatus(code.getDefaultStatus());
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
