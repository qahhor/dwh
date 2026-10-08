package com.smartup24.cms.instance.kauth;

import static com.smartup24.cms.instance.kauth.AuthenticationGenerationFixture.OLD_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.service.KauthSessionCleanupWorker;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Plan 10/10, item 7.5 (ADR-0034): a cookie session past its absolute lifetime or idle longer than the idle timeout
 * gets 401 through the production filter chain with no housekeeping worker running; the clock moves, not the rows.
 */
@Testcontainers
class KauthSessionExpiryHttpTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    static DataSource ds;
    AuthenticationGenerationFixture f;
    AnnotationConfigWebApplicationContext web;
    MockMvc mvc;
    Long userId;

    @BeforeAll
    static void migrate() {
        ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setup() {
        f = new AuthenticationGenerationFixture(ds);
        web = new AnnotationConfigWebApplicationContext();
        web.setParent(f.context);
        web.setServletContext(new MockServletContext());
        web.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("http-test", Map.of("smc.rate-limit.enabled", "false")));
        web.register(AuthenticationGenerationHttpTest.HttpConfiguration.class);
        web.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        userId = f.user(false, false);
    }

    @AfterEach
    void close() {
        if (web != null) web.close();
        if (f != null) f.close();
    }

    @Test
    @DisplayName("an active session older than the absolute lifetime gets 401; the cookie lives as long")
    void sessionPastAbsoluteLifetimeIsRefused() throws Exception {
        Cookie session = login();
        assertThat((long) session.getMaxAge())
                .isEqualTo(f.sessionProperties.absoluteTtl().toSeconds());

        // Used every 11 hours: never idle, yet the absolute lifetime of 7 days still ends it.
        Duration step = Duration.ofHours(11);
        Duration elapsed = Duration.ZERO;
        while (elapsed.plus(step).compareTo(f.sessionProperties.absoluteTtl()) < 0) {
            f.clock.advance(step);
            elapsed = elapsed.plus(step);
            me(session).andExpect(status().isOk());
        }
        f.clock.advance(f.sessionProperties.absoluteTtl().minus(elapsed).plusMinutes(1));

        me(session).andExpect(status().isUnauthorized());
        assertThat(openSessions())
                .as("no worker closed it: the query refused it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a session idle longer than the idle timeout gets 401; one used inside it stays valid")
    void idleSessionIsRefused() throws Exception {
        Cookie session = login();
        Duration idle = f.sessionProperties.idleTimeout();

        f.clock.advance(idle.minusMinutes(1));
        me(session).andExpect(status().isOk());

        f.clock.advance(idle.minusMinutes(1));
        me(session).andExpect(status().isOk());

        f.clock.advance(idle.plusMinutes(1));
        me(session).andExpect(status().isUnauthorized());
        assertThat(openSessions())
                .as("no worker closed it: the query refused it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the last activity is written at most once per touch interval")
    void lastActivityWriteIsThrottled() throws Exception {
        Cookie session = login();
        Instant signedIn = lastSeen();

        me(session).andExpect(status().isOk());
        me(session).andExpect(status().isOk());
        assertThat(lastSeen()).as("requests inside the interval write nothing").isEqualTo(signedIn);

        f.clock.advance(f.sessionProperties.touchInterval().plusSeconds(1));
        me(session).andExpect(status().isOk());
        assertThat(lastSeen()).isAfter(signedIn);
    }

    @Test
    @DisplayName("the housekeeping worker only closes sessions the query already refuses")
    void workerClosesOnlyExpiredSessions() throws Exception {
        Cookie stale = login();
        f.clock.advance(f.sessionProperties.idleTimeout().minusMinutes(30));
        Cookie fresh = login();
        f.clock.advance(Duration.ofMinutes(31));

        me(stale).andExpect(status().isUnauthorized());
        me(fresh).andExpect(status().isOk());
        assertThat(openSessions()).isEqualTo(2);

        new KauthSessionCleanupWorker(f.context.getBean(KauthSessionService.class)).cleanupExpiredSessions();

        assertThat(openSessions()).isEqualTo(1);
        me(fresh).andExpect(status().isOk());
    }

    private Cookie login() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(f.mapper.writeValueAsString(Map.of(
                                "login",
                                f.users.findById(userId).orElseThrow().login(),
                                "password",
                                OLD_PASSWORD,
                                "deviceInfo",
                                "test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("success"))
                .andReturn()
                .getResponse();
        Cookie session = response.getCookie(KauthPref.SESSION_COOKIE_NAME);
        assertThat(session).isNotNull();
        return session;
    }

    private ResultActions me(Cookie session) throws Exception {
        return mvc.perform(get("/api/v1/auth/me").cookie(session));
    }

    private long openSessions() {
        return f.jdbc.sql("select count(*) from kauth_sessions where user_id = :id and closed_at is null")
                .param("id", userId)
                .query(Long.class)
                .single();
    }

    private Instant lastSeen() {
        return f.jdbc.sql("select max(last_seen_at) from kauth_sessions where user_id = :id")
                .param("id", userId)
                .query(Timestamp.class)
                .single()
                .toInstant();
    }
}
