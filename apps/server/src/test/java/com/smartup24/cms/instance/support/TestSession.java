package com.smartup24.cms.instance.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.config.idempotency.IdempotencyFilter;
import com.smartup24.cms.instance.kauth.api.KauthPref;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * A signed-in browser session against the whole application (ADR-0032, 11.3): the session and XSRF cookies, the XSRF
 * header and an idempotency key go with every request, as the web client sends them, and the idempotency filter runs
 * as in production. Shared by the data-scope tests (ADR-0013) and the entity contract kit, instead of a private
 * {@code login()} per test class.
 */
public final class TestSession {

    public static final ObjectMapper JSON = new ObjectMapper();

    private final MockMvc mvc;
    private final Cookie session;
    private final Cookie csrf;
    private final String login;

    private TestSession(MockMvc mvc, Cookie session, Cookie csrf, String login) {
        this.mvc = mvc;
        this.session = session;
        this.csrf = csrf;
        this.login = login;
    }

    /** Signs {@code login} in with the test password ({@link TestUsers#PASSWORD}) through the real login endpoint. */
    public static TestSession signIn(WebApplicationContext wac, String login) throws Exception {
        return signIn(wac, login, TestUsers.PASSWORD);
    }

    /** Signs {@code login} in with {@code password} through the real login endpoint. */
    public static TestSession signIn(WebApplicationContext wac, String login, String password) throws Exception {
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
                                Map.of("login", login, "password", password, "deviceInfo", "test"))))
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
        return new TestSession(mvc, session, csrf, login);
    }

    /** The login this session is signed in as. */
    public String login() {
        return login;
    }

    /** Sends the request as the signed-in user with a fresh idempotency key. */
    public MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return send(request, UUID.randomUUID());
    }

    /** Sends the request with a JSON body as the signed-in user with a fresh idempotency key. */
    public MockHttpServletResponse send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return send(json(request, body), UUID.randomUUID());
    }

    /**
     * Sends the request with a JSON body and the given idempotency key, as a client repeating a request does; without
     * a key (null) the request is not idempotent, as a body over the idempotency limit must be sent.
     */
    public MockHttpServletResponse send(
            MockHttpServletRequestBuilder request, Object body, @Nullable UUID idempotencyKey) throws Exception {
        return send(json(request, body), idempotencyKey);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, @Nullable UUID idempotencyKey)
            throws Exception {
        request.cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue());
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey.toString());
        return mvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType("application/json").content(JSON.writeValueAsString(body));
    }

    /** The JSON object of a response. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    /** The JSON array of a response. */
    @SuppressWarnings("unchecked")
    public static List<Object> array(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), List.class);
    }
}
