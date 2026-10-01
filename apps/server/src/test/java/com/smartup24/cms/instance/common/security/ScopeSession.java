package com.smartup24.cms.instance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.config.idempotency.IdempotencyFilter;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * A signed-in browser session against the whole application for the data-scope tests (ADR-0013): the session and
 * XSRF cookies, the XSRF header and a fresh idempotency key go with every request, as the web client sends them.
 */
final class ScopeSession {

    static final ObjectMapper JSON = new ObjectMapper();

    private final MockMvc mvc;
    private final Cookie session;
    private final Cookie csrf;

    private ScopeSession(MockMvc mvc, Cookie session, Cookie csrf) {
        this.mvc = mvc;
        this.session = session;
        this.csrf = csrf;
    }

    /** Signs {@code login} in with the fixture password through the real login endpoint. */
    static ScopeSession signIn(WebApplicationContext wac, String login) throws Exception {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity());
        IdempotencyFilter idempotency =
                wac.getBeanProvider(IdempotencyFilter.class).getIfAvailable();
        if (idempotency != null) {
            builder.addFilters(idempotency);
        }
        MockMvc mvc = builder.build();
        var response = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(JSON.writeValueAsString(
                                Map.of("login", login, "password", ScopeFixture.PASSWORD, "deviceInfo", "test"))))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        Cookie session = response.getCookie(KauthPref.SESSION_COOKIE_NAME);
        Cookie csrf = response.getCookie("XSRF-TOKEN");
        if (csrf == null) {
            csrf = mvc.perform(get("/api/v1/auth/me").cookie(session))
                    .andReturn()
                    .getResponse()
                    .getCookie("XSRF-TOKEN");
        }
        assertThat(csrf).as("XSRF-TOKEN cookie").isNotNull();
        return new ScopeSession(mvc, session, csrf);
    }

    /** Sends the request as the signed-in user. */
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        request.cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        return mvc.perform(request).andReturn().getResponse();
    }

    /** Sends the request with a JSON body as the signed-in user. */
    MockHttpServletResponse send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return send(request.contentType("application/json").content(JSON.writeValueAsString(body)));
    }
}
