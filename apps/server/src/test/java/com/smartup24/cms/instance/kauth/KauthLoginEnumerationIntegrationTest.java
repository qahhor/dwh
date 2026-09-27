package com.smartup24.cms.instance.kauth;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.support.TestDatabases;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static com.smartup24.cms.instance.kauth.AuthenticationGenerationFixture.OLD_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan 10/10, item 0.6: a login attempt must not tell which accounts exist, and a refused attempt must leave its
 * trace. Runs through the real Spring transaction proxy: without one the rollback that erased the trace is invisible.
 */
class KauthLoginEnumerationIntegrationTest {

    static AuthenticationGenerationFixture f;

    @BeforeAll
    static void setUp() {
        f = new AuthenticationGenerationFixture(TestDatabases.migratedCopy("dwh_login_enumeration"));
    }

    @AfterAll
    static void tearDown() {
        f.close();
    }

    @Test
    @DisplayName("Five wrong passwords lock the login: the refused attempts are no longer rolled back")
    void fiveWrongPasswordsLockTheLogin() {
        Long id = f.user(false, false);
        String login = login(id);

        for (int i = 0; i < 5; i++) {
            String wrong = "Wrong-Password-" + i;
            assertRefused(() -> f.auth.login(login, wrong, "10.2.0.1", "ua", "web"),
                    ErrorCode.INVALID_CREDENTIALS);
        }

        assertRefused(() -> f.auth.login(login, OLD_PASSWORD, "10.2.0.1", "ua", "web"), ErrorCode.LOGIN_LOCKED);
        assertThat(securityEvents(id, "LOGIN_FAILED")).as("every refusal is in the security log").isEqualTo(5);
    }

    @Test
    @DisplayName("A blocked account shows its state only to someone who knows the password")
    void blockedStateNeedsThePassword() {
        Long id = f.user(false, false);
        f.jdbc.sql("update md_users set state = 'P' where id = :id").param("id", id).update();

        assertRefused(() -> f.auth.login(login(id), "Wrong-Password-1", "10.2.0.2", "ua", "web"),
                ErrorCode.INVALID_CREDENTIALS);
        assertRefused(() -> f.auth.login(login(id), OLD_PASSWORD, "10.2.0.3", "ua", "web"), ErrorCode.USER_BLOCKED);
    }

    @Test
    @DisplayName("A wrong one-time code uses up an attempt: the decrement is no longer rolled back")
    void wrongOtpUsesAnAttempt() {
        Long id = f.user(false, true);
        var started = f.auth.login(login(id), OLD_PASSWORD, "10.2.0.4", "ua", "web");
        String code = f.deliveredCodes.get(id);
        String wrong = code.equals("000000") ? "111111" : "000000";

        assertRefused(() -> f.auth.verifyOtp(started.otpToken(), wrong, "10.2.0.4", "ua", "web"), ErrorCode.OTP_INVALID);

        assertThat(f.jdbc.sql("select attempts_left from kauth_otp_codes where user_id = :id and purpose = 'login'")
                .param("id", id).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("An unknown login and a wrong password answer alike: same code, p95 apart by less than 50 ms")
    void unknownLoginLooksLikeWrongPassword() {
        Long id = f.user(false, false);
        String known = login(id);
        for (int i = 0; i < 3; i++) {  // warm-up
            attempt(known, "10.2.1." + i);
            attempt("nobody-" + UUID.randomUUID(), "10.2.1." + i);
        }

        long[] wrongPassword = new long[20];
        long[] unknownLogin = new long[20];
        for (int i = 0; i < wrongPassword.length; i++) {
            // A fresh address each time and the attempts cleared: the lockout must not cut the loop short.
            f.jdbc.sql("delete from kauth_login_attempts").update();
            wrongPassword[i] = attempt(known, "10.2.2." + i);
            unknownLogin[i] = attempt("nobody-" + UUID.randomUUID(), "10.2.3." + i);
        }

        assertThat(Math.abs(p95(wrongPassword) - p95(unknownLogin)) / 1_000_000).isLessThan(50);
    }

    private static long attempt(String login, String ip) {
        long start = System.nanoTime();
        assertRefused(() -> f.auth.login(login, "Wrong-Password-0", ip, "ua", "web"), ErrorCode.INVALID_CREDENTIALS);
        return System.nanoTime() - start;
    }

    private static void assertRefused(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private static String login(Long id) {
        return f.jdbc.sql("select login from md_users where id = :id").param("id", id).query(String.class).single();
    }

    private static long securityEvents(Long userId, String eventType) {
        return f.jdbc.sql("select count(*) from security_events where user_id = :id and event_type = :type")
                .param("id", userId).param("type", eventType).query(Long.class).single();
    }

    private static long p95(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * 0.95) - 1];
    }
}
