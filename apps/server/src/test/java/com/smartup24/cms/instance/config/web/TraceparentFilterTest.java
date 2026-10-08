package com.smartup24.cms.instance.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Plan 10/10, item 7.1: only a strict W3C traceparent continues a trace; anything else starts a new one. */
class TraceparentFilterTest {

    private static final String VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final TraceparentFilter filter = new TraceparentFilter();

    @Test
    @DisplayName("7.1: валидный traceparent и его tracestate проходят дальше без изменений")
    void passesAValidTraceparent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(TraceparentFilter.HEADER_TRACEPARENT, VALID);
        request.addHeader(TraceparentFilter.HEADER_TRACESTATE, "vendor=1");

        HttpServletRequest seen = run(request);

        assertThat(seen.getHeader("traceparent")).isEqualTo(VALID);
        assertThat(seen.getHeader("tracestate")).isEqualTo("vendor=1");
    }

    @Test
    @DisplayName("7.1: запрос без traceparent идёт дальше как есть")
    void passesARequestWithoutTraceContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");

        assertThat(run(request)).isSameAs(request);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", // another version
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01", // zero trace id
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", // zero parent id
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01", // upper case
                "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01", // short trace id
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1", // one-digit flags
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra", // trailing data
                " 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", // padding
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902bz-01", // not hex
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\r\nX-Injected: 1", // header injection
            })
    @DisplayName("7.1: невалидный traceparent скрывается вместе с tracestate, значение дальше не попадает")
    void hidesAnInvalidTraceparent(String value) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(TraceparentFilter.HEADER_TRACEPARENT, value);
        request.addHeader(TraceparentFilter.HEADER_TRACESTATE, "vendor=1");
        request.addHeader("Accept", "application/json");

        HttpServletRequest seen = run(request);

        assertThat(seen.getHeader("traceparent")).isNull();
        assertThat(seen.getHeader("TraceParent")).isNull();
        assertThat(seen.getHeader("tracestate")).isNull();
        assertThat(Collections.list(seen.getHeaders("traceparent"))).isEmpty();
        assertThat(Collections.list(seen.getHeaderNames()))
                .contains("Accept")
                .noneMatch(name -> name.equalsIgnoreCase("traceparent") || name.equalsIgnoreCase("tracestate"));
    }

    @Test
    @DisplayName("7.1: два заголовка traceparent — неоднозначный контекст, начинается новая трасса")
    void hidesARepeatedTraceparent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(TraceparentFilter.HEADER_TRACEPARENT, VALID);
        request.addHeader(TraceparentFilter.HEADER_TRACEPARENT, VALID);

        assertThat(run(request).getHeader("traceparent")).isNull();
    }

    @Test
    @DisplayName("7.1: фильтр не пишет traceparent в ответ и не заводит MDC сам")
    void neverEchoesTheHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(TraceparentFilter.HEADER_TRACEPARENT, "garbage");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(org.slf4j.MDC.get("traceparent")).isNull();
            assertThat(org.slf4j.MDC.get("client_code")).isNull();
        });

        assertThat(response.getHeader("traceparent")).isNull();
    }

    private HttpServletRequest run(MockHttpServletRequest request) throws ServletException, IOException {
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        return seen.get();
    }
}
