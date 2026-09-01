package com.greenwhite.dwh.cp.error;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CpRequestTraceFilterTest {

    private final CpRequestTraceFilter filter = new CpRequestTraceFilter();

    @Test
    void acceptsValidW3cTraceIdAndCorrelatesRequestResponseAndMdc() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fleet");
        request.addHeader(
                CpRequestTraceFilter.TRACEPARENT_HEADER,
                "00-" + traceId + "-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedMdc = new AtomicReference<>();
        FilterChain chain = (req, res) -> capturedMdc.set(MDC.get(CpRequestTraceFilter.MDC_TRACE_ID));

        filter.doFilter(request, response, chain);

        assertThat(capturedMdc).hasValue(traceId);
        assertThat(request.getAttribute(CpRequestTraceFilter.TRACE_ID_ATTRIBUTE)).isEqualTo(traceId);
        assertThat(response.getHeader(CpRequestTraceFilter.TRACE_ID_HEADER)).isEqualTo(traceId);
        assertThat(MDC.get(CpRequestTraceFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    void rejectsMalformedOrAllZeroTraceIdsAndGeneratesLowercaseNonZeroIds() throws Exception {
        for (String traceparent : new String[]{
                "not-a-traceparent",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fleet");
            request.addHeader(CpRequestTraceFilter.TRACEPARENT_HEADER, traceparent);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> { });

            String generated = response.getHeader(CpRequestTraceFilter.TRACE_ID_HEADER);
            assertThat(generated)
                    .matches("[0-9a-f]{32}")
                    .isNotEqualTo("00000000000000000000000000000000");
        }
    }
}
