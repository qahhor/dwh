package com.greenwhite.dwh.cp.config;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.security.CpAuthEntryPoint;
import com.greenwhite.dwh.cp.security.CpAuthFilter;
import com.greenwhite.dwh.cp.security.CpSpaCsrfHandler;
import com.greenwhite.dwh.cp.security.CpRoleInterceptor;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Безопасность control plane: те же правила, что у экземпляра (ADR-0008) —
 * cookie-сессия + CSRF double-submit, заголовки безопасности.
 * Отличие: контур сотрудников платформы, роли простые (CpPref), без матрицы форм.
 */
@Configuration
@EnableWebSecurity
public class CpSecurityConfig implements WebMvcConfigurer {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/instances/heartbeat",  // экземпляры шлют со своим токеном
            "/api/v1/instances/backup-report",
            "/error"
    };

    private final CpRoleInterceptor roleInterceptor;

    public CpSecurityConfig(CpRoleInterceptor roleInterceptor) {
        this.roleInterceptor = roleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleInterceptor).addPathPatterns("/api/**");
    }

    @Bean
    SecurityFilterChain cpFilterChain(HttpSecurity http, CpAuthFilter authFilter,
                                      CpAuthEntryPoint entryPoint) throws Exception {
        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CpSpaCsrfHandler())
                        // Запросы без сессионной cookie вектору CSRF не подвержены:
                        // это вход и heartbeat экземпляров с собственным токеном.
                        .ignoringRequestMatchers(CpSecurityConfig::noSessionCookie))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/actuator/**").permitAll()  // отдельный порт, наружу не публикуется
                        .anyRequest().authenticated())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; object-src 'none'"))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Permissions-Policy", "geolocation=(), camera=(), microphone=()")))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .addFilterAfter(authFilter, SecurityContextHolderFilter.class);

        return http.build();
    }

    /** Фильтр объявлен @Component ради DI — в servlet-контейнере его не регистрируем. */
    @Bean
    FilterRegistrationBean<CpAuthFilter> cpAuthFilterAutoRegistrationDisabled(CpAuthFilter filter) {
        FilterRegistrationBean<CpAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    private static boolean noSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return true;
        }
        for (Cookie c : cookies) {
            if (CpPref.SESSION_COOKIE_NAME.equals(c.getName())) {
                return false;
            }
        }
        return true;
    }
}
