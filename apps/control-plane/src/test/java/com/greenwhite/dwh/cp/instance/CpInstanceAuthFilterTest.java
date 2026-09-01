package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpRequestTraceFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CpInstanceAuthFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private CpInstanceCredentialService credentials;
    private CpInstanceAuthFilter filter;

    @BeforeEach
    void setUp() {
        credentials = mock(CpInstanceCredentialService.class);
        filter = new CpInstanceAuthFilter(
                credentials,
                new CpInstanceAuthenticationEntryPoint(new ObjectMapper()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validCredentialCreatesInstanceAuthentication() throws Exception {
        var principal = new CpInstancePrincipal(11L, 7L, "alpha", 31L);
        when(credentials.authenticate("raw-token")).thenReturn(Optional.of(principal));
        MockHttpServletRequest request = protectedRequest("POST", "/api/v1/instances/heartbeat");
        request.addHeader(CpInstanceAuthFilter.TOKEN_HEADER, "raw-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> captured = new AtomicReference<>();
        FilterChain chain = (req, res) ->
                captured.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getPrincipal()).isEqualTo(principal);
        assertThat(captured.get().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_INSTANCE");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingCredentialReturnsCorrelatedProblemWithoutCallingTheChain() throws Exception {
        MockHttpServletRequest request = protectedRequest(
                "GET", "/api/v1/instances/desired-state");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertInvalidCredential(response, called);
        verify(credentials, never()).authenticate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blankOrUnknownCredentialReturnsTheSameNonLeakingProblem() throws Exception {
        for (String rawToken : new String[]{"   ", "revoked-or-expired-secret"}) {
            MockHttpServletRequest request = protectedRequest(
                    "POST", "/api/v1/instances/credentials/rotate");
            request.addHeader(CpInstanceAuthFilter.TOKEN_HEADER, rawToken);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();
            when(credentials.authenticate(rawToken)).thenReturn(Optional.empty());

            filter.doFilter(request, response, (req, res) -> called.set(true));

            assertInvalidCredential(response, called);
            if (!rawToken.isBlank()) {
                assertThat(response.getContentAsString()).doesNotContain(rawToken);
            }
        }
        verify(credentials, never()).authenticate("   ");
    }

    @Test
    void enrollmentAndOperatorPathsAreOutsideTheInstanceCredentialFilter() throws Exception {
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{
                protectedRequest("POST", "/api/v1/instances/enroll"),
                protectedRequest("GET", "/api/v1/clients"),
                protectedRequest("GET", "/api/v1/instances/heartbeat")
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();

            filter.doFilter(request, response, (req, res) -> called.set(true));

            assertThat(called).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }
        verify(credentials, never()).authenticate(org.mockito.ArgumentMatchers.any());
    }

    private static MockHttpServletRequest protectedRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute(CpRequestTraceFilter.TRACE_ID_ATTRIBUTE, TRACE_ID);
        return request;
    }

    private static void assertInvalidCredential(MockHttpServletResponse response,
                                                AtomicBoolean called) throws Exception {
        assertThat(called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        var problem = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(problem.get("errorCode").asText())
                .isEqualTo("instance_credential_invalid");
        assertThat(problem.get("traceId").asText()).isEqualTo(TRACE_ID);
    }
}
