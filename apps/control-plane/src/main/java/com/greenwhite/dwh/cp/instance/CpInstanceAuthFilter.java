package com.greenwhite.dwh.cp.instance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Component
public class CpInstanceAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Instance-Token";

    private static final Set<String> PROTECTED_REQUESTS = Set.of(
            "POST /api/v1/instances/heartbeat",
            "POST /api/v1/instances/backup-reports",
            "GET /api/v1/instances/desired-state",
            "POST /api/v1/instances/credentials/rotate");

    private final CpInstanceCredentialService credentials;
    private final CpInstanceAuthenticationEntryPoint entryPoint;

    public CpInstanceAuthFilter(CpInstanceCredentialService credentials,
                                CpInstanceAuthenticationEntryPoint entryPoint) {
        this.credentials = credentials;
        this.entryPoint = entryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PROTECTED_REQUESTS.contains(request.getMethod() + " " + request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String rawCredential = request.getHeader(TOKEN_HEADER);
        if (rawCredential == null || rawCredential.isBlank()) {
            entryPoint.writeInvalidCredential(request, response);
            return;
        }

        var principal = credentials.authenticate(rawCredential);
        if (principal.isEmpty()) {
            entryPoint.writeInvalidCredential(request, response);
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal.get(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INSTANCE")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
