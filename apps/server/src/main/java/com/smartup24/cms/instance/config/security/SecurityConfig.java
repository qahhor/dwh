package com.smartup24.cms.instance.config.security;

import com.smartup24.cms.instance.common.security.ClientIpResolver;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.security.TrustedProxyProperties;
import com.smartup24.cms.instance.config.idempotency.IdempotencyFilter;
import com.smartup24.cms.instance.kauth.api.KauthRequestAuthentication;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({RateLimitProperties.class, TrustedProxyProperties.class})
public class SecurityConfig {

    /**
     * The paths open without a sign-in. The API description ({@code /api/v1/openapi.json}) is not one of them: it names
     * every entity and field of the installation, so only a signed-in user reads it (product owner, 2026-10-03).
     */
    private static final String[] PUBLIC_PATHS = {
        "/api/v1/auth/login", "/api/v1/auth/otp", "/api/v1/auth/password-reset/**", "/error"
    };

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookieName("XSRF-TOKEN");
        repository.setCookiePath("/");
        // Lax like the session cookie: the web still reads the token for the double submit, and a cross-site
        // request never carries it (ADR-0034, plan 10/10, item 7.7).
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }

    @Bean
    public ClientIpResolver clientIpResolver(TrustedProxyProperties properties) {
        return new ClientIpResolver(properties);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            KauthRequestAuthentication kauthAuthentication,
            RateLimitFilter rateLimitFilter,
            IdempotencyFilter idempotencyFilter,
            CookieCsrfTokenRepository tokenRepository,
            ProblemDetailAuthHandlers problemHandlers)
            throws Exception {

        http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> {
                    CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
                    requestHandler.setCsrfRequestAttributeName(null);

                    csrf.csrfTokenRepository(tokenRepository)
                            .csrfTokenRequestHandler(requestHandler)
                            // Kauth revalidates credentials on every stateless request. Completed
                            // login/OTP and logout own CSRF renewal/clearing in KauthAuthController.
                            .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                            // FR-SEC-1: CSRF applies to mutating requests WITH cookie authentication.
                            // Only an accepted API token exempts a request that carries a session cookie.
                            .ignoringRequestMatchers(request -> isCsrfExempt(request, kauthAuthentication));
                })
                .authorizeHttpRequests(auth -> auth
                        // ASYNC/ERROR are continuations of an already-authorized request. Re-authorizing
                        // them after an SSE/client disconnect can only produce a second, committed response.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/i18n/languages")
                        .permitAll()
                        .requestMatchers(SecurityConfig::isPublicI18nDictionaryRead)
                        .permitAll()
                        .requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        // Actuator lives on a separate management port that is not published outside
                        .requestMatchers("/actuator/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .anonymous(Customizer.withDefaults())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; object-src 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .httpStrictTransportSecurity(
                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Permissions-Policy", "geolocation=(), camera=(), microphone=()")))
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers))
                // The order is deterministic: authentication -> rate limits -> idempotency -> authorization
                .addFilterAfter(kauthAuthentication, SecurityContextHolderFilter.class)
                .addFilterBefore(rateLimitFilter, AuthorizationFilter.class)
                .addFilterAfter(idempotencyFilter, RateLimitFilter.class);

        return http.build();
    }

    @Bean
    FilterRegistrationBean<KauthRequestAuthentication> kauthFilterAutoRegistrationDisabled(
            KauthRequestAuthentication filter) {
        FilterRegistrationBean<KauthRequestAuthentication> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterAutoRegistrationDisabled(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilterAutoRegistrationDisabled(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private static boolean isCsrfExempt(HttpServletRequest request, KauthRequestAuthentication authentication) {
        var principal = SecurityContext.getPrincipal();
        if (principal != null && principal.isApi()) {
            return true;
        }
        return !authentication.carriesSessionCookie(request);
    }

    private static boolean isPublicI18nDictionaryRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && request.getRequestURI().matches("^/api/v1/i18n/[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*$");
    }
}
