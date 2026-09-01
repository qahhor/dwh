package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpRequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CpInstanceAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String ERROR_CODE = "instance_credential_invalid";

    private final ObjectMapper objectMapper;

    public CpInstanceAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writeInvalidCredential(request, response);
    }

    void writeInvalidCredential(HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Object traceId = request.getAttribute(CpRequestTraceFilter.TRACE_ID_ATTRIBUTE);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://api.dwh.internal/errors/" + ERROR_CODE);
        problem.put("title", "Unauthorized");
        problem.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        problem.put("errorCode", ERROR_CODE);
        problem.put("detail", "Instance credential is invalid, expired or revoked");
        problem.put("instance", request.getRequestURI());
        problem.put("traceId", traceId != null ? traceId.toString() : "");

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
