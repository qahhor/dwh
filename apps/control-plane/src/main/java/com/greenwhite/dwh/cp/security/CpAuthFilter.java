package com.greenwhite.dwh.cp.security;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.repository.CpSessionRepository;
import com.greenwhite.dwh.cp.repository.CpUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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

/**
 * Аутентификация сотрудника по сессионной cookie. Участвует только в цепочке
 * Spring Security (авто-регистрация в контейнере отключена в CpSecurityConfig),
 * иначе выполнялся бы дважды на каждый запрос.
 */
@Component
public class CpAuthFilter extends OncePerRequestFilter {

    private final CpSessionRepository sessionRepository;
    private final CpUserRepository userRepository;

    public CpAuthFilter(CpSessionRepository sessionRepository, CpUserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String raw = sessionCookie(request);
            if (raw != null) {
                sessionRepository.findActive(raw).ifPresent(session ->
                        userRepository.findById(session.userId()).ifPresent(user -> {
                            if (CpPref.STATE_ACTIVE.equals(user.state())) {
                                sessionRepository.touch(session.id());
                                Set<String> roles = userRepository.getRoles(user.id());
                                authenticate(new CpSecurityContext.CpPrincipal(
                                        user.id(), user.login(), user.name(), session.id(), roles));
                            }
                        }));
            }
            chain.doFilter(request, response);
        } finally {
            // SecurityContextHolder чистит SecurityContextHolderFilter самой цепочки
            CpSecurityContext.clear();
        }
    }

    private void authenticate(CpSecurityContext.CpPrincipal principal) {
        CpSecurityContext.set(principal);
        var authorities = principal.roles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        authorities.isEmpty() ? List.of(new SimpleGrantedAuthority("ROLE_CP")) : authorities));
    }

    private String sessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (CpPref.SESSION_COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
