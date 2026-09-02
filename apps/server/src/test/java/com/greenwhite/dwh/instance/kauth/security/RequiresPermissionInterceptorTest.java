package com.greenwhite.dwh.instance.kauth.security;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequiresPermissionInterceptorTest {

    private final RequiresPermissionInterceptor interceptor = new RequiresPermissionInterceptor();
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void asyncContinuationDoesNotRepeatPermissionCheckWithoutThreadLocalContext() throws Exception {
        when(request.getDispatcherType()).thenReturn(DispatcherType.ASYNC);

        assertThat(interceptor.preHandle(request, response, securedHandler())).isTrue();
    }

    @Test
    void initialRequestStillRequiresAuthentication() throws Exception {
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, securedHandler()))
                .isInstanceOf(ApiException.class);
    }

    private static HandlerMethod securedHandler() throws NoSuchMethodException {
        return new HandlerMethod(new SecuredController(), SecuredController.class.getDeclaredMethod("secured"));
    }

    static class SecuredController {
        @RequiresPermission(form = "test.form", action = "view")
        void secured() {
        }
    }
}
