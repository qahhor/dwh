package com.greenwhite.dwh.instance.config.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class TraceparentFilterTest {

    private final TraceparentFilter filter = new TraceparentFilter();

    @Test
    @DisplayName("Фильтр должен генерировать валидный W3C traceparent и проставлять его в ответ и MDC")
    void shouldGenerateValidTraceparentWhenMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            String mdcTraceparent = MDC.get(TraceparentFilter.MDC_TRACEPARENT);
            String mdcTraceId = MDC.get(TraceparentFilter.MDC_TRACE_ID);
            String mdcClientCode = MDC.get(TraceparentFilter.MDC_CLIENT_CODE);

            assertThat(mdcTraceparent).isNotNull().startsWith("00-").endsWith("-01");
            assertThat(mdcTraceId).isNotNull().hasSize(32);
            assertThat(mdcClientCode).isEqualTo("default");
        });

        String headerResponse = response.getHeader(TraceparentFilter.HEADER_TRACEPARENT);
        assertThat(headerResponse).isNotNull().startsWith("00-").endsWith("-01");

        // MDC must be cleaned up after request
        assertThat(MDC.get(TraceparentFilter.MDC_TRACEPARENT)).isNull();
        assertThat(MDC.get(TraceparentFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    @DisplayName("Фильтр должен сохранять входящий валидный traceparent и извлекать X-Client-Code")
    void shouldPreserveIncomingTraceparent() throws ServletException, IOException {
        String incomingTp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks/items");
        request.addHeader(TraceparentFilter.HEADER_TRACEPARENT, incomingTp);
        request.addHeader(TraceparentFilter.HEADER_CLIENT_CODE, "acme_corp");

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(TraceparentFilter.MDC_TRACEPARENT)).isEqualTo(incomingTp);
            assertThat(MDC.get(TraceparentFilter.MDC_TRACE_ID)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(MDC.get(TraceparentFilter.MDC_CLIENT_CODE)).isEqualTo("acme_corp");
        });

        assertThat(response.getHeader(TraceparentFilter.HEADER_TRACEPARENT)).isEqualTo(incomingTp);
    }
}
