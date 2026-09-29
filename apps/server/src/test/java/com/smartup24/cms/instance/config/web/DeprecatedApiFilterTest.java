package com.smartup24.cms.instance.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.web.ApiDeprecations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Plan 10/10, item 3.4 (ADR-0023): a deprecated form answers as before and says what replaces it. */
class DeprecatedApiFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeprecatedApiFilter filter = new DeprecatedApiFilter(provider(meters));

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meters", registry);
        return beans.getBeanProvider(MeterRegistry.class);
    }

    @Test
    @DisplayName("3.4: a path alias answers with Deprecation, Sunset and the successor in Link")
    void pathAliasNamesItsSuccessor() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/api/v1/tasks/items/42/comments"));

        assertThat(response.getHeader(DeprecatedApiFilter.DEPRECATION)).isEqualTo("@1790640000");
        assertThat(response.getHeader(DeprecatedApiFilter.SUNSET)).isEqualTo("Thu, 31 Dec 2026 00:00:00 GMT");
        assertThat(response.getHeader(DeprecatedApiFilter.LINK))
                .isEqualTo("</api/v1/tasks/42/comments>; rel=\"successor-version\"");
        assertThat(meters.get("dwh_api_deprecated_calls_total")
                        .tag("alias", "/api/v1/tasks/items/{*rest}")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("3.4: a current path and a current method on a shared path are left alone")
    void currentFormsCarryNoDeprecation() throws Exception {
        assertThat(run(new MockHttpServletRequest("GET", "/api/v1/tasks/42"))
                        .getHeader(DeprecatedApiFilter.DEPRECATION))
                .isNull();
        assertThat(run(new MockHttpServletRequest("PUT", "/api/v1/notes/7/pin"))
                        .getHeader(DeprecatedApiFilter.DEPRECATION))
                .isNull();
        assertThat(run(new MockHttpServletRequest("POST", "/api/v1/announcements"))
                        .getHeader(DeprecatedApiFilter.DEPRECATION))
                .isNull();
    }

    @Test
    @DisplayName("3.4: a replaced toggle names the PUT that replaced it")
    void toggleNamesItsPut() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("POST", "/api/v1/modules/notes/toggle"));

        assertThat(response.getHeader(DeprecatedApiFilter.LINK))
                .isEqualTo("</api/v1/modules/notes/enabled>; rel=\"successor-version\"");
        assertThat(ApiDeprecations.successor("POST", "/api/v1/modules/notes/toggle"))
                .get()
                .extracting(ApiDeprecations.Successor::method)
                .isEqualTo("PUT");
    }

    @Test
    @DisplayName("3.4: a successor whose variable is in the body is named without a Link")
    void successorWithBodyVariableHasNoLink() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("POST", "/api/v1/modules"));

        assertThat(response.getHeader(DeprecatedApiFilter.DEPRECATION)).isNotNull();
        assertThat(response.getHeader(DeprecatedApiFilter.LINK)).isNull();
        assertThat(ApiDeprecations.successor("POST", "/api/v1/modules"))
                .get()
                .extracting(ApiDeprecations.Successor::path)
                .isEqualTo("/api/v1/modules/{code}");
    }

    @Test
    @DisplayName("3.4: the more specific session alias wins over the generic one")
    void specificSessionAliasWins() {
        assertThat(ApiDeprecations.successor("GET", "/api/v1/iam/profile/sessions/users/5/security"))
                .get()
                .extracting(ApiDeprecations.Successor::path)
                .isEqualTo("/api/v1/iam/users/5/security");
        assertThat(ApiDeprecations.successor("DELETE", "/api/v1/iam/profile/sessions/users/5/9"))
                .get()
                .extracting(ApiDeprecations.Successor::path)
                .isEqualTo("/api/v1/iam/users/5/sessions/9");
        assertThat(ApiDeprecations.successor("GET", "/api/v1/iam/profile/sessions"))
                .isEmpty();
        assertThat(ApiDeprecations.successor("GET", "/api/v1/iam/sessions"))
                .get()
                .extracting(ApiDeprecations.Successor::path)
                .isEqualTo("/api/v1/iam/profile/sessions");
    }

    @Test
    @DisplayName("3.4: a snake_case query parameter reaches the handler under its camelCase name")
    void snakeCaseParameterIsRenamed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setQueryString("project_id=3&q=x");
        request.addParameter("project_id", "3");
        request.addParameter("q", "x");
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(seen.get().getParameter("projectId")).isEqualTo("3");
        assertThat(seen.get().getParameter("project_id")).isNull();
        assertThat(seen.get().getParameter("q")).isEqualTo("x");
        assertThat(response.getHeader(DeprecatedApiFilter.DEPRECATION)).isNotNull();
        assertThat(response.getHeader(DeprecatedApiFilter.LINK)).isNull();
    }

    @Test
    @DisplayName("3.4: the camelCase name wins when a request sends both")
    void camelCaseWinsOverSnakeCase() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setQueryString("project_id=3&projectId=4");
        request.addParameter("project_id", "3");
        request.addParameter("projectId", "4");
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(seen.get().getParameterValues("projectId")).containsExactly("4");
        assertThat(seen.get().getParameterMap()).containsOnlyKeys("projectId");
    }

    @Test
    @DisplayName("3.4: a request with current names and paths passes through untouched")
    void currentRequestIsNotWrapped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setQueryString("projectId=3");
        request.addParameter("projectId", "3");
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(seen.get()).isSameAs(request);
        assertThat(response.getHeaderNames()).isEmpty();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response;
    }
}
