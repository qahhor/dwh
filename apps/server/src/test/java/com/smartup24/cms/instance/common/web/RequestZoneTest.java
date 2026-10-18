package com.smartup24.cms.instance.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The time zone of a request (ADR-0042): a region the header names, UTC for anything else; never an error. */
class RequestZoneTest {

    @Test
    void aRegionOfTheZoneDatabaseIsTakenAnythingElseIsUtc() {
        assertThat(RequestZone.parse("Asia/Tashkent")).isEqualTo(ZoneId.of("Asia/Tashkent"));
        assertThat(RequestZone.parse(null)).isEqualTo(ZoneOffset.UTC);
        assertThat(RequestZone.parse("Mars/Olympus")).isEqualTo(ZoneOffset.UTC);
        assertThat(RequestZone.parse("../etc")).isEqualTo(ZoneOffset.UTC);
        assertThat(RequestZone.parse("x".repeat(100))).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void theHeaderOfTheCurrentRequestNamesTheZone() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestZone.HEADER, "Europe/Berlin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThat(RequestZone.current()).isEqualTo(ZoneId.of("Europe/Berlin"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
