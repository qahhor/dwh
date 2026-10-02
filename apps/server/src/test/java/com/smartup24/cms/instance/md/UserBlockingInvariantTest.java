package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.kauth.repository.KauthApiTokenRepository;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.kauth.service.KauthUserSessionInvalidator;
import com.smartup24.cms.instance.support.TestDatabases;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * FR-USR-4: blocking a user closes all of their sessions and revokes all of their API tokens.
 * Implementation: the action block of the user entity, MdUserSecurityService.revokeAccess, the UserSessionInvalidator
 * port, then
 * KauthUserSessionInvalidator (in the same transaction).
 */
class UserBlockingInvariantTest {

    static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        var ds = TestDatabases.migratedCopy("smc_block_test");
        jdbc = JdbcClient.create(ds);
    }

    @Test
    @DisplayName("I-U1: инвалидация закрывает все сессии и отзывает все токены пользователя")
    void invalidatorClosesSessionsAndRevokesTokens() {
        Long userId = jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values ('Block Test', 'block_test', 'block@test.local', 'x', 'A', 'ru', 'UTC',
                                '{}'::jsonb, false, false)
                        returning id
                        """).query(Long.class).single();

        jdbc.sql("""
                insert into kauth_sessions (user_id, token_hash, ip, user_agent)
                values (:u, 'sh1', '127.0.0.1'::inet, 'ua'), (:u, 'sh2', '127.0.0.1'::inet, 'ua')
                """).param("u", userId).update();
        jdbc.sql("""
                insert into kauth_api_tokens (user_id, name, token_prefix, token_hash)
                values (:u, 'integration', 'pfx1', 'th1'), (:u, 'backup', 'pfx2', 'th2')
                """).param("u", userId).update();

        new KauthUserSessionInvalidator(new KauthSessionRepository(jdbc), new KauthApiTokenRepository(jdbc))
                .invalidateAllAccess(userId);

        Long openSessions = jdbc.sql("select count(*) from kauth_sessions where user_id = :u and closed_at is null")
                .param("u", userId)
                .query(Long.class)
                .single();
        Long activeTokens = jdbc.sql("select count(*) from kauth_api_tokens where user_id = :u and revoked_at is null")
                .param("u", userId)
                .query(Long.class)
                .single();

        assertThat(openSessions).as("открытых сессий после блокировки").isZero();
        assertThat(activeTokens).as("активных токенов после блокировки").isZero();
    }
}
