package com.greenwhite.dwh.instance.config.security;

import com.greenwhite.dwh.instance.kauth.pref.KauthPref;
import com.greenwhite.dwh.instance.kauth.security.KauthAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Каркас безопасности (ремедиация R2, ADR-0008):
 * - аутентификация — KauthAuthenticationFilter (cookie-сессии + Bearer) внутри цепочки Spring Security;
 * - CSRF double-submit (FR-SEC-1): cookie XSRF-TOKEN + заголовок X-XSRF-TOKEN (Angular-совместимо);
 *   освобождены Bearer-запросы и запросы без сессионной cookie;
 * - заголовки безопасности (FR-SEC-5);
 * - 401/403 — RFC 9457 problem+json (ProblemDetailAuthHandlers).
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/auth/otp",
            "/api/v1/auth/password-reset/**",
            "/error"
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            KauthAuthenticationFilter kauthAuthenticationFilter,
            RateLimitFilter rateLimitFilter,
            ProblemDetailAuthHandlers problemHandlers) throws Exception {

        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> {
                    CookieCsrfTokenRepository tokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
                    tokenRepository.setHeaderName("X-XSRF-TOKEN");
                    tokenRepository.setCookieName("XSRF-TOKEN");
                    tokenRepository.setCookiePath("/");

                    CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
                    requestHandler.setCsrfRequestAttributeName(null);

                    csrf.csrfTokenRepository(tokenRepository)
                            .csrfTokenRequestHandler(requestHandler)
                            // FR-SEC-1: CSRF применяется к мутирующим запросам С cookie-аутентификацией.
                            // Bearer-запросы и запросы без сессионной cookie вектору не подвержены.
                            .ignoringRequestMatchers(SecurityConfig::isCsrfExempt);
                })

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Actuator живёт на отдельном management-порту, наружу не публикуется
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .anonymous(Customizer.withDefaults())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; object-src 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Permissions-Policy", "geolocation=(), camera=(), microphone=()")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                // Порядок детерминирован разными якорями: аутентификация — сразу после
                // установки контекста; лимиты — после неё (нужна личность), до авторизации,
                // чтобы превышение отвечало 429, а не 401/403.
                .addFilterAfter(kauthAuthenticationFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(rateLimitFilter, AuthorizationFilter.class);

        return http.build();
    }

    /**
     * Фильтр объявлен @Component ради DI, но участвовать должен только в цепочке
     * Spring Security — авто-регистрацию в servlet-контейнере выключаем,
     * иначе он выполнялся бы дважды на каждый запрос.
     */
    @Bean
    FilterRegistrationBean<KauthAuthenticationFilter> kauthFilterAutoRegistrationDisabled(
            KauthAuthenticationFilter filter) {
        FilterRegistrationBean<KauthAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterAutoRegistrationDisabled(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private static boolean isCsrfExempt(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return true;
        }
        return !hasSessionCookie(request);
    }

    private static boolean hasSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (KauthPref.SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }
}
