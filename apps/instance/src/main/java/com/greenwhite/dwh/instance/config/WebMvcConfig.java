package com.greenwhite.dwh.instance.config;

import com.greenwhite.dwh.instance.kauth.security.RequiresPermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequiresPermissionInterceptor permissionInterceptor;

    public WebMvcConfig(RequiresPermissionInterceptor permissionInterceptor) {
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
                        "/api/v1/health/**"
                );
    }
}
