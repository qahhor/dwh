package com.smartup24.cms.instance.config.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.support.TestModules;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

class IdempotencyModuleAccessTest {

    private final RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
    private final QueryListRegistry lists = mock(QueryListRegistry.class);
    private final IdempotencyModuleAccess access = new IdempotencyModuleAccess(
            TestModules.catalog(),
            code -> !code.equals("tasks") && !code.equals("notes"),
            mock(EntityRegistry.class),
            lists,
            new ObjectMapper(),
            new StaticListableBeanFactory(Map.of("mapping", mapping))
                    .getBeanProvider(RequestMappingHandlerMapping.class));

    @Test
    void missingHandlerCannotReplay() {
        refused(request("/removed"), "{}");
    }

    @Test
    void failedHandlerLookupCannotReplay() throws Exception {
        when(mapping.getHandler(any())).thenThrow(new IllegalStateException("lookup unavailable"));
        refused(request("/unavailable"), "{}");
    }

    @Test
    void unknownPermissionAreaCannotReplay() throws Exception {
        handler("unknown");
        refused(request("/unknown"), "{}");
    }

    @Test
    void analyticsBelongsToTheDisabledTasksModule() throws Exception {
        handler("analytics");
        refused(request("/analytics"), "{}");
    }

    @Test
    void unknownEntityCannotReplay() throws Exception {
        handler("core");
        var request = request("/api/v1/entities/{code}");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("code", "unknown.records"));
        refused(request, "{}");
    }

    @Test
    void missingEntityCodeCannotReplay() throws Exception {
        handler("core");
        refused(request("/api/v1/entities/{code}"), "{}");
    }

    @Test
    void unknownExportListCannotReplay() throws Exception {
        handler("core");
        refused(request("/api/v1/exports"), "{\"list\":\"unknown.list\"}");
    }

    @Test
    void malformedExportBodyCannotReplay() throws Exception {
        handler("core");
        refused(request("/api/v1/exports"), "{");
        refused(request("/api/v1/exports"), "{}");
    }

    @Test
    void customExportListUsesItsOwnPermissionArea() throws Exception {
        handler("core");
        QueryList list = mock(QueryList.class);
        when(list.form()).thenReturn("notes.items");
        when(lists.find("custom.notes")).thenReturn(Optional.of(list));
        refused(request("/api/v1/exports"), "{\"list\":\"custom.notes\"}");
        when(list.form()).thenReturn("md.users");
        assertThatCode(() -> access.requireActive(request("/api/v1/exports"), bytes("{\"list\":\"custom.notes\"}")))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnrelatedCodeParameterDoesNotBecomeAnEntity() throws Exception {
        handler("core");
        var request = request("/api/v1/modules/{code}/enabled");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("code", "tasks"));
        assertThatCode(() -> access.requireActive(request, bytes("{}"))).doesNotThrowAnyException();
    }

    @Test
    void handlerWithoutModulePermissionIsUnaffected() throws Exception {
        handler("open");
        assertThatCode(() -> access.requireActive(request("/open"), bytes("{}")))
                .doesNotThrowAnyException();
    }

    private void handler(String method) throws Exception {
        when(mapping.getHandler(any()))
                .thenReturn(new HandlerExecutionChain(
                        new HandlerMethod(new Fixture(), Fixture.class.getDeclaredMethod(method))));
    }

    private static MockHttpServletRequest request(String pattern) {
        var request = new MockHttpServletRequest("POST", pattern);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return request;
    }

    private void refused(MockHttpServletRequest request, String body) {
        assertThatThrownBy(() -> access.requireActive(request, bytes(body)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static class Fixture {
        @RequiresPermission(form = "md.profile", action = "view")
        void core() {}

        @RequiresPermission(form = "unknown.form", action = "view")
        void unknown() {}

        @RequiresPermission(form = "analytics.dashboard", action = "view")
        void analytics() {}

        void open() {}
    }
}
