package com.greenwhite.dwh.instance.kauth.security;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequiresPermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequiresPermission annotation = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequiresPermission.class);
        }

        if (annotation == null) {
            return true;
        }

        if (!SecurityContext.isAuthenticated()) {
            throw ApiException.unauthorized("Требуется авторизация для доступа к ресурсу");
        }

        if (!SecurityContext.hasPermission(annotation.form(), annotation.action())) {
            throw ApiException.permissionDenied(annotation.form(), annotation.action());
        }

        return true;
    }
}
