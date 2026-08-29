package com.greenwhite.dwh.cp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Неаутентифицированный запрос — 401, а не 403.
 * По умолчанию цепочка без formLogin/httpBasic отдаёт 403, и клиент
 * не может отличить «войди» от «тебе нельзя».
 */
@Component
public class CpAuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"about:blank","title":"UNAUTHORIZED","status":401,"code":"unauthorized",\
                "detail":"Требуется вход в control plane","instance":"%s"}"""
                .formatted(request.getRequestURI()));
    }
}
