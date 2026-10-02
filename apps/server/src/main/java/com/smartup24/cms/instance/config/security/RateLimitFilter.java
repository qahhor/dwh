package com.smartup24.cms.instance.config.security;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.ClientIpResolver;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.service.SearchMetrics;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchRateBudget;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request rate limits (FR-SEC-2): per IP for unauthenticated requests,
 * per user for cookie sessions, per token owner for Bearer;
 * a separate (stricter) limit for expensive paths. Exceeding it gives 429 with
 * Retry-After (RFC 9457 body) and a rate_limit_exceeded event in the
 * security log (flood-protected: at most once a minute per key).
 *
 * Sits in the chain AFTER KauthAuthenticationFilter (it needs the identity) and
 * BEFORE AuthorizationFilter, so exceeding the limit answers 429, not 401/403.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String EVENT_RATE_LIMIT_EXCEEDED = "rate_limit_exceeded";

    private final RateLimitProperties props;
    private final RateLimitService rateLimitService;
    private final SearchPolicyProvider searchPolicyProvider;
    private final AuditLogService auditLogService;
    private final ProblemDetailAuthHandlers problemWriter;
    private final ClientIpResolver clientIpResolver;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private SearchMetrics searchMetrics = SearchMetrics.unmetered();

    @Autowired
    public RateLimitFilter(
            RateLimitProperties props,
            RateLimitService service,
            SearchPolicyProvider policies,
            AuditLogService audit,
            ProblemDetailAuthHandlers problems,
            ClientIpResolver clientIpResolver,
            Optional<SearchMetrics> metrics) {
        this(props, service, policies, audit, problems, clientIpResolver);
        this.searchMetrics = metrics.orElseGet(SearchMetrics::unmetered);
    }

    public RateLimitFilter(
            RateLimitProperties props,
            RateLimitService rateLimitService,
            SearchPolicyProvider searchPolicyProvider,
            AuditLogService auditLogService,
            ProblemDetailAuthHandlers problemWriter,
            ClientIpResolver clientIpResolver) {
        this.props = props;
        this.rateLimitService = rateLimitService;
        this.searchPolicyProvider = searchPolicyProvider;
        this.auditLogService = auditLogService;
        this.problemWriter = problemWriter;
        this.clientIpResolver = clientIpResolver != null ? clientIpResolver : new ClientIpResolver(null);
    }

    public RateLimitFilter(
            RateLimitProperties props,
            RateLimitService rateLimitService,
            SearchPolicyProvider searchPolicyProvider,
            AuditLogService auditLogService,
            ProblemDetailAuthHandlers problemWriter) {
        this(props, rateLimitService, searchPolicyProvider, auditLogService, problemWriter, new ClientIpResolver(null));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.enabled() || request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        var principal = SecurityContext.getPrincipal();
        Quota quota = baseQuota(principal, request);
        String key = quota.key();
        int limit = quota.limit();

        SearchRateBudget searchBudget = null;
        if (isInteractiveSearch(request)) {
            if (principal != null) {
                key = key + ":search";
                try {
                    searchBudget = searchPolicyProvider.effectiveBudget(limit);
                } catch (ApiException unavailable) {
                    if (unavailable.getErrorCode() != ErrorCode.SERVICE_UNAVAILABLE) throw unavailable;
                    problemWriter.writeProblem(
                            request,
                            response,
                            ErrorCode.SERVICE_UNAVAILABLE,
                            "error.search_config_unavailable",
                            Map.of());
                    return;
                }
                limit = searchBudget.perMinute();
            }
        } else {
            String expensivePathFamily =
                    isSearchCategoryRead(request) ? null : findExpensivePathFamily(request.getRequestURI());
            if (expensivePathFamily != null) {
                key = key + ":exp:" + expensivePathFamily;
                limit = Math.min(limit, props.expensivePerMinute());
            }
        }

        ConsumptionProbe probe = searchBudget == null
                ? rateLimitService.tryConsume(key, limit)
                : rateLimitService.tryConsume(key, searchBudget.perMinute(), searchBudget.capacity());
        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }
        reject(request, response, principal, key, limit, probe);
    }

    private record Quota(String key, int limit) {}

    /** The bucket of the caller before path rules: IP, token owner or user. */
    private Quota baseQuota(SecurityContext.KauthPrincipal principal, HttpServletRequest request) {
        if (principal == null) {
            if (isPublicI18nRead(request)) {
                return new Quota("ip:" + clientIp(request) + ":public-read", props.publicReadPerMinute());
            }
            return new Quota("ip:" + clientIp(request), props.ipPerMinute());
        }
        if (principal.isApi()) {
            // Limit per token owner: one service account equals one integration
            return new Quota("api:" + principal.userId(), props.tokenPerMinute());
        }
        return new Quota("user:" + principal.userId(), props.userPerMinute());
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            SecurityContext.KauthPrincipal principal,
            String key,
            int limit,
            ConsumptionProbe probe)
            throws IOException {
        long retryAfterSec = Math.max(1, Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L));
        if (isInteractiveSearch(request)) searchMetrics.rejected();
        if (rateLimitService.shouldLogRejection(key)) {
            auditLogService.logSecurityEvent(
                    EVENT_RATE_LIMIT_EXCEEDED,
                    principal != null ? principal.userId() : null,
                    clientIp(request),
                    request.getHeader("User-Agent"),
                    Map.of("path", request.getRequestURI(), "key", key, "limit", limit));
        }
        response.setHeader("Retry-After", String.valueOf(retryAfterSec));
        problemWriter.writeProblem(
                request,
                response,
                ErrorCode.RATE_LIMITED,
                "error.rate_limited_retry",
                Map.of("seconds", retryAfterSec));
    }

    private String findExpensivePathFamily(String uri) {
        for (String pattern : props.expensivePaths()) {
            if (pathMatcher.match(pattern, uri)) {
                return pattern;
            }
        }
        return null;
    }

    private static boolean isInteractiveSearch(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return ("GET".equalsIgnoreCase(request.getMethod()) && "/api/v1/search".equals(uri))
                || ("POST".equalsIgnoreCase(request.getMethod()) && "/api/v1/search/preview".equals(uri));
    }

    /** The searchable entities are read from the registry on every palette open: the user budget (ADR-0032, 10.3). */
    private static boolean isSearchCategoryRead(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod()) && "/api/v1/search/entities".equals(request.getRequestURI());
    }

    private boolean isPublicI18nRead(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return "/api/v1/i18n/languages".equals(uri) || uri.matches("/api/v1/i18n/[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*");
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolveClientIp(request);
    }
}
