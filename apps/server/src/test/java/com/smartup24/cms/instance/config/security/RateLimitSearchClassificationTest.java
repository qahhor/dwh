package com.smartup24.cms.instance.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR-0008, 2.2: which search requests leave the expensive family for the user budget. */
class RateLimitSearchClassificationTest {

    @ParameterizedTest(name = "{0} {1} -> user budget: {2}")
    @CsvSource({
        "GET, /api/v1/search/entities, true",
        "GET, /api/v1/search/settings, true",
        "GET, /api/v1/search/jobs, true",
        "GET, /api/v1/search/jobs/6f1c2a4e-8d3b-4c5a-9e7f-0a1b2c3d4e5f, true",
        "get, /api/v1/search/jobs/6F1C2A4E-8D3B-4C5A-9E7F-0A1B2C3D4E5F, true",
        "GET, /api/v1/search/status, false",
        "GET, /api/v1/search, false",
        "GET, /api/v1/search/jobs/, false",
        "GET, /api/v1/search/jobs/not-a-job, false",
        "GET, /api/v1/search/jobs/6f1c2a4e-8d3b-4c5a-9e7f-0a1b2c3d4e5f/cancel, false",
        "PUT, /api/v1/search/settings, false",
        "POST, /api/v1/search/jobs, false",
        "POST, /api/v1/search/preview, false",
        "POST, /api/v1/search/jobs/6f1c2a4e-8d3b-4c5a-9e7f-0a1b2c3d4e5f/retry, false",
        "POST, /api/v1/search/jobs/6f1c2a4e-8d3b-4c5a-9e7f-0a1b2c3d4e5f/cancel, false",
        "GET, /api/v1/audit/logs, false"
    })
    void classifiesSearchRequests(String method, String uri, boolean userBudget) {
        assertThat(RateLimitFilter.isSearchManagementRead(method, uri)).isEqualTo(userBudget);
    }

    @Test
    void requestWithoutPathStaysExpensive() {
        assertThat(RateLimitFilter.isSearchManagementRead("GET", null)).isFalse();
    }
}
