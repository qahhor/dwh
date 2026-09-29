package com.smartup24.cms.instance.kauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import com.smartup24.cms.instance.support.TestDatabases;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A session is closed only by its own user: the id alone once closed anyone's session (IDOR). */
class KauthSessionOwnershipTest {

    private static JdbcClient jdbc;
    private static KauthSessionRepository sessions;
    private static KauthSessionService service;

    @BeforeAll
    static void setup() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("session_owner"));
        sessions = new KauthSessionRepository(jdbc);
        service = new KauthSessionService(sessions);
    }

    @Test
    void anotherUsersSessionIsNotFoundAndStaysOpen() {
        long alice = user("alice");
        long bob = user("bob");
        long bobSession = session(bob, "bob-token");

        assertNotFound(() -> service.closeUserSession(alice, bobSession));
        assertThat(open(bobSession)).as("bob's session is untouched").isTrue();

        service.closeUserSession(bob, bobSession);
        assertThat(open(bobSession)).isFalse();

        assertNotFound(() -> service.closeUserSession(bob, bobSession));
        assertNotFound(() -> service.closeUserSession(bob, 987_654L));
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(error.getMessageKey()).isEqualTo("error.auth.session_not_found");
        });
    }

    private static long user(String login) {
        return jdbc.sql("""
                insert into md_users (name, login, email) values (:login, :login, :login || '@example.test')
                returning id
                """).param("login", login).query(Long.class).single();
    }

    private static long session(long userId, String tokenHash) {
        long version = jdbc.sql("select auth_version from md_users where id = :id")
                .param("id", userId)
                .query(Long.class)
                .single();
        return sessions.create(userId, version, tokenHash, "127.0.0.1", "test", "test")
                .id();
    }

    private static boolean open(long sessionId) {
        return jdbc.sql("select closed_at is null from kauth_sessions where id = :id")
                .param("id", sessionId)
                .query(Boolean.class)
                .single();
    }
}
