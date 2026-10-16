package com.smartup24.cms.instance.kauth;

import static com.smartup24.cms.instance.kauth.AuthenticationGenerationFixture.OLD_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.kauth.service.KauthAuthService;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Account aliases and concurrent requests must share the same finite password-guess budget. */
class KauthLoginBudgetIntegrationTest {
    static AuthenticationGenerationFixture f;
    static AuthenticationGenerationFixture otherNode;

    @BeforeAll
    static void setUp() {
        var database = TestDatabases.migratedCopy("smc_login_budget");
        f = new AuthenticationGenerationFixture(database);
        otherNode = new AuthenticationGenerationFixture(database);
    }

    @AfterAll
    static void tearDown() {
        f.close();
        otherNode.close();
    }

    @Test
    void loginCaseWhitespaceAndEmailShareOneAccountBudget() {
        var user = account();
        List<String> aliases = List.of(
                user.login(),
                user.login().toUpperCase(Locale.ROOT),
                "  " + user.login() + "  ",
                user.email(),
                " " + user.email().toUpperCase(Locale.ROOT) + " ");
        for (int i = 0; i < aliases.size(); i++) {
            assertThat(attempt(aliases.get(i), "10.20.1." + i)).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        for (String alias : aliases) {
            assertThat(attempt(alias, "10.20.2.1")).isEqualTo(ErrorCode.LOGIN_LOCKED);
        }
        assertThat(f.context
                        .getBean(KauthSessionService.class)
                        .getUserSecuritySummary(user.id(), user)
                        .recentLoginAttempts())
                .hasSize(5);
    }

    @Test
    void changingLoginAndEmailDoesNotResetTheAccountBudget() {
        var user = account();
        for (int i = 0; i < 5; i++) {
            assertThat(attempt(user.login(), "10.20.3." + i)).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }
        String replacement = "renamed_" + UUID.randomUUID();
        f.jdbc.sql("update md_users set login = :login, email = :email where id = :id")
                .param("login", replacement)
                .param("email", replacement + "@example.invalid")
                .param("id", user.id())
                .update();

        assertThat(attempt(replacement, "10.20.4.1")).isEqualTo(ErrorCode.LOGIN_LOCKED);
    }

    @Test
    void unknownLoginVariantsShareTheSameBudgetAndResponse() {
        String unknown = "missing_" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            String alias = i % 2 == 0 ? unknown : " " + unknown.toUpperCase(Locale.ROOT) + " ";
            assertThat(attempt(alias, "10.20.5." + i)).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }
        assertThat(attempt(unknown, "10.20.6.1")).isEqualTo(ErrorCode.LOGIN_LOCKED);
    }

    @Test
    void parallelAliasesCannotSpendMoreThanFiveGuesses() throws Exception {
        var user = account();
        List<Callable<ErrorCode>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String alias = i % 2 == 0 ? user.login() : user.email();
            String ip = "10.20.7." + i;
            var node = i % 2 == 0 ? f : otherNode;
            calls.add(() -> attempt(node.auth, alias, ip));
        }

        assertThat(parallel(calls))
                .filteredOn(code -> code == ErrorCode.INVALID_CREDENTIALS)
                .hasSize(5);
    }

    @Test
    void parallelUnknownAccountsCannotSpendMoreThanTenGuessesPerIp() throws Exception {
        List<Callable<ErrorCode>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String unknown = "missing_" + UUID.randomUUID();
            var node = i % 2 == 0 ? f : otherNode;
            String ip = i % 2 == 0 ? "2001:db8::1" : "2001:0db8:0:0:0:0:0:1";
            calls.add(() -> attempt(node.auth, unknown, ip));
        }

        assertThat(parallel(calls))
                .filteredOn(code -> code == ErrorCode.INVALID_CREDENTIALS)
                .hasSize(10);
    }

    @Test
    void expiredFailuresAllowPasswordAndSecondFactorAgain() {
        Long id = f.user(false, true);
        var user = f.context.getBean(MdUserService.class).findAuthUserById(id).orElseThrow();
        for (int i = 0; i < 5; i++) {
            assertThat(attempt(user.login(), "10.20.9." + i)).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }
        f.jdbc.sql("update kauth_login_attempts set attempt_at = now() - interval '11 minutes' where login = :login")
                .param("login", user.login())
                .update();

        var result = f.auth.login(" " + user.login().toUpperCase(Locale.ROOT), OLD_PASSWORD, "10.20.10.1", "ua", "web");

        assertThat(result.isOtpRequired()).isTrue();
        assertThat(result.rawSessionCookie()).isNull();
        assertThat(f.deliveredCodes).containsKey(id);
    }

    private static MdUserService.AuthUser account() {
        Long id = f.user(false, false);
        f.jdbc.sql("update md_users set email = login || '@example.invalid' where id = :id")
                .param("id", id)
                .update();
        return f.context.getBean(MdUserService.class).findAuthUserById(id).orElseThrow();
    }

    private static ErrorCode attempt(String login, String ip) {
        return attempt(f.auth, login, ip);
    }

    private static ErrorCode attempt(KauthAuthService auth, String login, String ip) {
        try {
            auth.login(login, "Wrong-Password-2026", ip, "ua", "web");
        } catch (ApiException refusal) {
            return refusal.getErrorCode();
        }
        throw new AssertionError("wrong password was accepted");
    }

    private static List<ErrorCode> parallel(List<Callable<ErrorCode>> calls) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (var call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ErrorCode> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }
}
