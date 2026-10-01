package com.smartup24.cms.instance.config.web;

import static com.smartup24.cms.instance.common.web.ApiDeprecations.ANY;
import static com.smartup24.cms.instance.common.web.ApiDeprecations.alias;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.web.ApiDeprecations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Plan 10/10, item 3.4 (ADR-0023): a deprecated form answers as before and says what replaces it. No form is
 * deprecated now, so the machinery is checked on a fixture table and the current table must let everything through.
 */
class DeprecatedApiFilterTest {

    /** A table shaped like the forms a release may deprecate: aliases, a toggle, a body variable, a parameter. */
    static final ApiDeprecations FIXTURE = new ApiDeprecations(
            List.of(
                    alias(ANY, "/api/v1/old-items/{*rest}", ANY, "/api/v1/items{rest}"),
                    alias(ANY, "/api/v1/old-profile/users/{userId}/security", ANY, "/api/v1/users/{userId}/security"),
                    alias(ANY, "/api/v1/old-profile/users/{userId}/{id}", ANY, "/api/v1/users/{userId}/sessions/{id}"),
                    alias(ANY, "/api/v1/old-profile/{*rest}", ANY, "/api/v1/profile{rest}"),
                    alias("POST", "/api/v1/widgets/{code}/toggle", "PUT", "/api/v1/widgets/{code}/enabled"),
                    alias("POST", "/api/v1/widgets", "PUT", "/api/v1/widgets/{code}")),
            Map.of("project_id", "projectId"));

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeprecatedApiFilter filter = new DeprecatedApiFilter(provider(meters), FIXTURE);

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meters", registry);
        return beans.getBeanProvider(MeterRegistry.class);
    }

    @Test
    @DisplayName("ADR-0023: no form is deprecated now, so the filter of the application changes no request")
    void currentTableIsEmpty() throws Exception {
        assertThat(ApiDeprecations.CURRENT.queryParameters()).isEmpty();
        assertThat(ApiDeprecations.CURRENT.successor("GET", "/api/v1/old-items/42"))
                .isEmpty();
        assertThat(ApiDeprecations.CURRENT.currentNames(Map.of("project_id", "3")))
                .containsExactly(Map.entry("project_id", "3"));

        DeprecatedApiFilter current = new DeprecatedApiFilter(provider(meters));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setQueryString("project_id=3");
        request.addParameter("project_id", "3");
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        current.doFilter(request, response, (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(seen.get()).isSameAs(request);
        assertThat(response.getHeaderNames()).isEmpty();
    }

    @Test
    @DisplayName("3.4: a path alias answers with Deprecation, Sunset and the successor in Link")
    void pathAliasNamesItsSuccessor() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/api/v1/old-items/42/comments"));

        assertThat(response.getHeader(DeprecatedApiFilter.DEPRECATION)).isEqualTo("@1790640000");
        assertThat(response.getHeader(DeprecatedApiFilter.SUNSET)).isEqualTo("Thu, 31 Dec 2026 00:00:00 GMT");
        assertThat(response.getHeader(DeprecatedApiFilter.LINK))
                .isEqualTo("</api/v1/items/42/comments>; rel=\"successor-version\"");
        assertThat(meters.get("smc_api_deprecated_calls_total")
                        .tag("alias", "/api/v1/old-items/{*rest}")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("3.4: a current path and a current method on a shared path are left alone")
    void currentFormsCarryNoDeprecation() throws Exception {
        assertThat(run(new MockHttpServletRequest("GET", "/api/v1/items/42"))
                        .getHeader(DeprecatedApiFilter.DEPRECATION))
                .isNull();
        assertThat(run(new MockHttpServletRequest("PUT", "/api/v1/widgets/notes/toggle"))
                        .getHeader(DeprecatedApiFilter.DEPRECATION))
                .isNull();
        assertThat(run(new MockHttpServletRequest("GET", "/api/v1/widgets")).getHeader(DeprecatedApiFilter.DEPRECATION))
                .isNull();
    }

    @Test
    @DisplayName("3.4: a replaced toggle names the PUT that replaced it")
    void toggleNamesItsPut() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("POST", "/api/v1/widgets/notes/toggle"));

        assertThat(response.getHeader(DeprecatedApiFilter.LINK))
                .isEqualTo("</api/v1/widgets/notes/enabled>; rel=\"successor-version\"");
        assertThat(FIXTURE.successor("POST", "/api/v1/widgets/notes/toggle"))
                .get()
                .extracting(ApiDeprecations.Successor::method)
                .isEqualTo("PUT");
    }

    @Test
    @DisplayName("3.4: a successor whose variable is in the body is named without a Link")
    void successorWithBodyVariableHasNoLink() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("POST", "/api/v1/widgets"));

        assertThat(response.getHeader(DeprecatedApiFilter.DEPRECATION)).isNotNull();
        assertThat(response.getHeader(DeprecatedApiFilter.LINK)).isNull();
        assertThat(FIXTURE.successor("POST", "/api/v1/widgets"))
                .get()
                .extracting(ApiDeprecations.Successor::path)
                .isEqualTo("/api/v1/widgets/{code}");
    }

    @Test
    @DisplayName("3.4: the more specific alias wins over the generic one")
    void specificAliasWins() {
        assertThat(FIXTURE.successor("GET", "/api/v1/old-profile/users/5/security"))
                .get()
                .extracting(ApiDeprecations.Successor::path)
                .isEqualTo("/api/v1/users/5/security");
        assertThat(FIXTURE.successor("DELETE", "/api/v1/old-profile/users/5/9"))
                .get()
                .extracting(ApiDeprecations.Successor::path)
                .isEqualTo("/api/v1/users/5/sessions/9");
        assertThat(FIXTURE.successor("GET", "/api/v1/profile/sessions")).isEmpty();
        assertThat(FIXTURE.successor("GET", "/api/v1/old-profile/sessions"))
                .get()
                .extracting(ApiDeprecations.Successor::path)
                .isEqualTo("/api/v1/profile/sessions");
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
        assertThat(seen.get().getParameterNames().hasMoreElements()).isTrue();
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

    @Test
    @DisplayName("3.4: a malformed escape in a query name is not a legacy name and does not fail the filter")
    void malformedQueryNamePassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setQueryString("%zz=1");
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(seen.get()).isSameAs(request);
        assertThat(response.getHeader(DeprecatedApiFilter.DEPRECATION)).isNull();
        assertThat(DeprecatedApiFilter.decodedName("%zz")).isEqualTo("%zz");
    }

    @Test
    @DisplayName("3.4: a legacy name after a malformed one is still renamed")
    void legacyNameAfterMalformedOneIsRenamed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setQueryString("%zz=1&project_id=3");
        request.addParameter("project_id", "3");
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(seen.get().getParameter("projectId")).isEqualTo("3");
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response;
    }
}
