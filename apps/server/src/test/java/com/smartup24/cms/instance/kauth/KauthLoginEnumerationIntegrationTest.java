package com.smartup24.cms.instance.kauth;

import static com.smartup24.cms.instance.kauth.AuthenticationGenerationFixture.OLD_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 0.6: a login attempt must not tell which accounts exist, and a refused attempt must leave its
 * trace. Runs through the real Spring transaction proxy: without one the rollback that erased the trace is invisible,
 * and so is a second connection taken per request (parallel refusals must not exhaust the pool).
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
            assertRefused(() -> f.auth.login(login, wrong, "10.2.0.1", "ua", "web"), ErrorCode.INVALID_CREDENTIALS);
        }

        assertRefused(() -> f.auth.login(login, OLD_PASSWORD, "10.2.0.1", "ua", "web"), ErrorCode.LOGIN_LOCKED);
        assertThat(securityEvents(id, "LOGIN_FAILED"))
                .as("every refusal is in the security log")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("Attempts during the lock do not renew it: only refused credentials count")
    void attemptsDuringTheLockDoNotRenewIt() {
        Long id = f.user(false, false);
        String login = login(id);
        for (int i = 0; i < 5; i++) {
            String wrong = "Wrong-Password-" + i;
            assertRefused(() -> f.auth.login(login, wrong, "10.2.0.9", "ua", "web"), ErrorCode.INVALID_CREDENTIALS);
        }
        for (int i = 0; i < 3; i++) {
            assertRefused(() -> f.auth.login(login, OLD_PASSWORD, "10.2.0.9", "ua", "web"), ErrorCode.LOGIN_LOCKED);
        }

        assertThat(f.jdbc.sql("select count(*) from kauth_login_attempts where login = :login and not is_success")
                        .param("login", login)
                        .query(Long.class)
                        .single())
                .as("the lock ends ten minutes after the fifth wrong password, whatever happens meanwhile")
                .isEqualTo(5);
        assertThat(lockEvents(login)).isEqualTo(3);
    }

    @Test
    @DisplayName("Parallel guesses of a one-time code get no more comparisons than the code has attempts")
    void parallelGuessesAreLimited() throws Exception {
        Long id = f.user(false, true);
        var started = f.auth.login(login(id), OLD_PASSWORD, "10.2.0.10", "ua", "web");
        String code = f.deliveredCodes.get(id);
        String wrong = code.equals("000000") ? "111111" : "000000";

        int guesses = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < guesses; i++) {
            calls.add(() -> {
                start.await();
                try {
                    f.auth.verifyOtp(started.otpToken(), wrong, "10.2.0.10", "ua", "web");
                    return "accepted";
                } catch (ApiException e) {
                    return e.getMessageKey();
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (var call : calls) {
                results.add(pool.submit(call));
            }
            start.countDown();
            long compared = 0;
            for (var result : results) {
                if (result.get().equals("error.otp_invalid")) {
                    compared++;
                }
            }
            assertThat(compared).as("comparisons of a code with three attempts").isLessThanOrEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A blocked account shows its state only to someone who knows the password")
    void blockedStateNeedsThePassword() {
        Long id = f.user(false, false);
        f.jdbc.sql("update md_users set state = 'P' where id = :id")
                .param("id", id)
                .update();

        assertRefused(
                () -> f.auth.login(login(id), "Wrong-Password-1", "10.2.0.2", "ua", "web"),
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

        assertRefused(
                () -> f.auth.verifyOtp(started.otpToken(), wrong, "10.2.0.4", "ua", "web"), ErrorCode.OTP_INVALID);

        assertThat(f.jdbc.sql("select attempts_left from kauth_otp_codes where user_id = :id and purpose = 'login'")
                        .param("id", id)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("An unknown login and a wrong password answer alike: same code, p95 apart by less than 50 ms")
    void unknownLoginLooksLikeWrongPassword() {
        Long id = f.user(false, false);
        String known = login(id);
        for (int i = 0; i < 3; i++) { // warm-up
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

    @Test
    @DisplayName("3.1: an unknown login and a wrong password carry the same text key and no parameters")
    void unknownLoginAndWrongPasswordShareTheText() {
        Long id = f.user(false, false);
        f.jdbc.sql("delete from kauth_login_attempts").update();
        ApiException wrongPassword =
                refusal(() -> f.auth.login(login(id), "Wrong-Password-0", "10.2.4.1", "ua", "web"));
        ApiException unknownLogin =
                refusal(() -> f.auth.login("nobody-" + UUID.randomUUID(), "Wrong-Password-0", "10.2.4.2", "ua", "web"));

        assertThat(unknownLogin.getErrorCode()).isEqualTo(wrongPassword.getErrorCode());
        assertThat(unknownLogin.getMessageKey())
                .isEqualTo(wrongPassword.getMessageKey())
                .isEqualTo("error.invalid_credentials");
        assertThat(unknownLogin.getParams())
                .isEqualTo(wrongPassword.getParams())
                .isEmpty();
    }

    private static ApiException refusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("the call was not refused");
    }

    private static long attempt(String login, String ip) {
        long start = System.nanoTime();
        assertRefused(() -> f.auth.login(login, "Wrong-Password-0", ip, "ua", "web"), ErrorCode.INVALID_CREDENTIALS);
        return System.nanoTime() - start;
    }

    private static void assertRefused(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private static String login(Long id) {
        return f.jdbc.sql("select login from md_users where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private static long securityEvents(Long userId, String eventType) {
        return f.jdbc.sql("select count(*) from security_events where user_id = :id and event_type = :type")
                .param("id", userId)
                .param("type", eventType)
                .query(Long.class)
                .single();
    }

    private static long lockEvents(String login) {
        return f.jdbc.sql("""
                        select count(*) from security_events
                        where user_id is null and event_type = 'LOGIN_LOCKED' and details ->> 'login' = :login
                        """).param("login", login).query(Long.class).single();
    }

    private static long p95(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * 0.95) - 1];
    }
}
