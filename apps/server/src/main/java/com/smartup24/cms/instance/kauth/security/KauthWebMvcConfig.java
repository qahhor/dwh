package com.smartup24.cms.instance.kauth.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the permission check of every API handler. It lives with the interceptor, so the application wiring needs
 * no internal type of kauth (plan 10/10, item 1.3); the sign-in paths and health stay outside it.
 */
@Configuration
public class KauthWebMvcConfig implements WebMvcConfigurer {

    private final RequiresPermissionInterceptor permissionInterceptor;

    public KauthWebMvcConfig(RequiresPermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/otp",
                        "/api/v1/auth/password-reset/**",
                        "/api/v1/health/**");
    }
}
