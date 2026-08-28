package com.greenwhite.dwh.cp.security;

import com.greenwhite.dwh.cp.pref.CpPref;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import org.springframework.http.HttpStatus;

/** Проверка ролей по аннотации @CpRequiresRole. */
@Component
public class CpRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        CpRequiresRole required = method.getMethodAnnotation(CpRequiresRole.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(CpRequiresRole.class);
        }
        if (required == null) {
            return true;
        }
        if (!CpSecurityContext.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход в control plane");
        }
        // cp-admin проходит всегда: он по определению включает остальные роли
        if (CpSecurityContext.hasRole(CpPref.ROLE_ADMIN)) {
            return true;
        }
        for (String role : required.value()) {
            if (CpSecurityContext.hasRole(role)) {
                return true;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Недостаточно прав: требуется одна из ролей " + String.join(", ", required.value()));
    }
}
