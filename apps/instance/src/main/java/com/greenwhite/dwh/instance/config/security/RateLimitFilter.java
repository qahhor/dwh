package com.greenwhite.dwh.instance.config.security;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Лимиты частоты запросов (FR-SEC-2): по IP для неаутентифицированных,
 * по пользователю для cookie-сессий, по владельцу токена для Bearer;
 * отдельный (более строгий) лимит на дорогие пути. Превышение — 429 с
 * Retry-After (RFC 9457 body) и событием rate_limit_exceeded в
 * security-журнале (с анти-флудом: не чаще раза в минуту на ключ).
 *
 * Стоит в цепочке ПОСЛЕ KauthAuthenticationFilter (нужна личность) и
 * ДО AuthorizationFilter — превышение отвечает 429, а не 401/403.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String EVENT_RATE_LIMIT_EXCEEDED = "rate_limit_exceeded";

    private final RateLimitProperties props;
    private final RateLimitService rateLimitService;
    private final AuditLogService auditLogService;
    private final ProblemDetailAuthHandlers problemWriter;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(RateLimitProperties props,
                           RateLimitService rateLimitService,
                           AuditLogService auditLogService,
                           ProblemDetailAuthHandlers problemWriter) {
        this.props = props;
        this.rateLimitService = rateLimitService;
        this.auditLogService = auditLogService;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.enabled() || request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        var principal = SecurityContext.getPrincipal();
        String key;
        int limit;
        if (principal == null) {
            key = "ip:" + clientIp(request);
            limit = props.ipPerMinute();
        } else if (principal.isApi()) {
            // Лимит на владельца токена: сервисная учётка = одна интеграция (разд. 4.4.1 ТЗ-01)
            key = "api:" + principal.userId();
            limit = props.tokenPerMinute();
        } else {
            key = "user:" + principal.userId();
            limit = props.userPerMinute();
        }

        if (isExpensivePath(request.getRequestURI())) {
            key = key + ":exp";
            limit = Math.min(limit, props.expensivePerMinute());
        }

        ConsumptionProbe probe = rateLimitService.tryConsume(key, limit);
        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        if (rateLimitService.shouldLogRejection(key)) {
            auditLogService.logSecurityEvent(EVENT_RATE_LIMIT_EXCEEDED,
                    principal != null ? principal.userId() : null,
                    clientIp(request),
                    request.getHeader("User-Agent"),
                    Map.of("path", request.getRequestURI(), "key", key, "limit", limit));
        }
        response.setHeader("Retry-After", String.valueOf(retryAfterSec));
        problemWriter.writeProblem(response, ErrorCode.RATE_LIMITED,
                "Превышен лимит запросов, повторите через " + retryAfterSec + " с",
                request.getRequestURI());
    }

    private boolean isExpensivePath(String uri) {
        for (String pattern : props.expensivePaths()) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    private String clientIp(HttpServletRequest request) {
        // За обратным прокси (фаза P) сюда придёт заголовок от доверенного LB;
        // до этого используем адрес соединения.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
