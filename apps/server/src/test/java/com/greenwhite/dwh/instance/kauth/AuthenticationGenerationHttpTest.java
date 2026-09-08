package com.greenwhite.dwh.instance.kauth;

import com.greenwhite.dwh.instance.config.WebMvcConfig;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.config.error.GlobalExceptionHandler;
import com.greenwhite.dwh.instance.config.idempotency.*;
import com.greenwhite.dwh.instance.config.security.*;
import com.greenwhite.dwh.instance.kauth.controller.*;
import com.greenwhite.dwh.instance.kauth.pref.KauthPref;
import com.greenwhite.dwh.instance.kauth.security.*;
import com.greenwhite.dwh.instance.search.service.SearchPolicyProvider;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static com.greenwhite.dwh.instance.kauth.AuthenticationGenerationFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual production filter chain, permission interceptor, controllers and proxied JDBC use cases. */
@Testcontainers
class AuthenticationGenerationHttpTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:18-alpine");
    static DataSource ds;
    AuthenticationGenerationFixture f;
    AnnotationConfigWebApplicationContext web;
    MockMvc mvc;

    @BeforeAll static void migrate(){
        ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure()).dataSource(ds).locations("classpath:db/migration").load().migrate();
    }
    @BeforeEach void setup(){
        f=new AuthenticationGenerationFixture(ds);
        web=new AnnotationConfigWebApplicationContext();
        web.setParent(f.context);
        web.setServletContext(new MockServletContext());
        web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("http-test",Map.of("dwh.rate-limit.enabled","true")));
        web.register(HttpConfiguration.class);
        web.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
    }
    @AfterEach void close(){if(web!=null)web.close();if(f!=null)f.close();}

    static Stream<Arguments> paths(){
        return Stream.of("/api/v1/auth/password","/api/v1/iam/users/me/password")
                .flatMap(path -> Stream.of(Arguments.of(path,false,false),Arguments.of(path,true,false),
                        Arguments.of(path,false,true),Arguments.of(path,true,true)));
    }

    @ParameterizedTest @MethodSource("paths")
    void passwordAliasesRevokeCookieAndBearerThenRequireRealNewPasswordLogin(String path,boolean forced,boolean bearer) throws Exception {
        Long id=f.user(false,false);
        grantTokenPermission(id);
        var cookies=login(id,OLD_PASSWORD);
        String apiToken=createApiToken(cookies);
        f.jdbc.sql("update md_users set force_password_change=:forced where id=:id").param("forced",forced).param("id",id).update();
        MockHttpServletRequestBuilder change=post(path).contentType("application/json").content(passwordBody(OLD_PASSWORD,NEW_PASSWORD));
        if(bearer)change.header("Authorization","Bearer "+apiToken);else csrf(change,cookies);
        mvc.perform(change).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(cookies.session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+apiToken)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(loginBody(id,OLD_PASSWORD))).andExpect(status().isUnauthorized());
        var fresh=login(id,NEW_PASSWORD);
        mvc.perform(get("/api/v1/auth/me").cookie(fresh.session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.forcePasswordChange").value(false));
        assertThat(f.users.findById(id).orElseThrow().authenticationVersion()).isEqualTo(1);
        assertThat(f.count("security_events",id,"event_type='PASSWORD_CHANGED'")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"/api/v1/auth/password","/api/v1/iam/users/me/password"})
    void anonymousCsrfWrongPasswordAndPasswordPolicyErrorsPreserveAccess(String path) throws Exception {
        Long id=f.user(true,false);
        var cookies=login(id,OLD_PASSWORD);
        String body=passwordBody(OLD_PASSWORD,NEW_PASSWORD);
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).cookie(cookies.session).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(csrf(post(path),cookies).contentType("application/json").content(passwordBody(OTHER_PASSWORD,NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(csrf(post(path),cookies).contentType("application/json").content(passwordBody(OLD_PASSWORD,"password123")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/auth/me").cookie(cookies.session)).andExpect(status().isOk());
        assertThat(f.users.findById(id).orElseThrow().authenticationVersion()).isZero();
        assertThat(f.users.findById(id).orElseThrow().forcePasswordChange()).isTrue();
        assertThat(f.count("security_events",id,"event_type='PASSWORD_CHANGED'")).isZero();
    }

    @Test void permissionInterceptorStillRejectsMissingPermissionAndForcedChange() throws Exception {
        Long id=f.user(false,false);
        var cookies=login(id,OLD_PASSWORD);
        mvc.perform(csrf(post("/api/v1/iam/profile/tokens"),cookies).contentType("application/json").content("{\"name\":\"test\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("permission_denied"));
        grantTokenPermission(id);
        f.jdbc.sql("update md_users set force_password_change=true where id=:id").param("id",id).update();
        mvc.perform(csrf(post("/api/v1/iam/profile/tokens"),cookies).contentType("application/json").content("{\"name\":\"test\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("must_change_password"));
        // Own password needs authentication but no business permission, even in forced-change state.
        mvc.perform(csrf(post("/api/v1/auth/password"),cookies).contentType("application/json").content(passwordBody(OLD_PASSWORD,NEW_PASSWORD)))
                .andExpect(status().isNoContent());
    }

    @Test void logoutClosesOnlyItsCookieSession() throws Exception {
        Long id=f.user(false,false);grantTokenPermission(id);
        var first=login(id,OLD_PASSWORD);var second=login(id,OLD_PASSWORD);
        String token=createApiToken(first);
        mvc.perform(csrf(post("/api/v1/auth/logout"),first)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(first.session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(second.session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertThat(f.users.findById(id).orElseThrow().authenticationVersion()).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completedLoginRenewsAnonymousCsrfAndSupportsImmediatePasswordChange(boolean forced) throws Exception {
        Long id = f.user(forced, false);
        Cookie anonymousCsrf = anonymousCsrf();
        var response = mvc.perform(post("/api/v1/auth/login").cookie(anonymousCsrf)
                        .contentType("application/json").content(loginBody(id, OLD_PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.step").value("success"))
                .andReturn().getResponse();
        var cookies = completedLoginCookies(response);
        assertThat(cookies.csrf.getValue().equals(anonymousCsrf.getValue()))
                .as("completed credential login renews the anonymous CSRF token").isFalse();
        assertThat(csrfWrites(response)).isEqualTo(1);
        assertThat(cookies.csrf.isHttpOnly()).isFalse();
        assertThat(cookies.csrf.getPath()).isEqualTo("/");

        // Browser uses the new cookie: an old extracted header must not authorize a mutation.
        mvc.perform(post("/api/v1/auth/password").cookie(cookies.session, cookies.csrf)
                        .header("X-XSRF-TOKEN", anonymousCsrf.getValue()).contentType("application/json")
                        .content(passwordBody(OLD_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_token_invalid"));
        assertThat(f.users.findById(id).orElseThrow().authenticationVersion()).isZero();
        mvc.perform(csrf(post("/api/v1/auth/password"), cookies).contentType("application/json")
                        .content(passwordBody(OLD_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());
        assertThat(f.users.findById(id).orElseThrow().authenticationVersion()).isEqualTo(1);
    }

    @Test void completedOtpRenewsCsrfButChallengeAndFailureDoNot() throws Exception {
        Long id = f.user(false, true);
        Cookie anonymousCsrf = anonymousCsrf();
        var challenge = mvc.perform(post("/api/v1/auth/login").cookie(anonymousCsrf)
                        .contentType("application/json").content(loginBody(id, OLD_PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.step").value("otp"))
                .andReturn().getResponse();
        assertThat(csrfWrites(challenge)).isZero();
        assertThat(lastCookie(challenge, KauthPref.SESSION_COOKIE_NAME) == null).isTrue();
        String otpToken = f.mapper.readTree(challenge.getContentAsString()).get("otp_token").asText();
        String code = f.deliveredCodes.get(id);
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        var failed = mvc.perform(post("/api/v1/auth/otp").cookie(anonymousCsrf)
                        .contentType("application/json").content(otpBody(otpToken, wrongCode)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("otp_invalid"))
                .andReturn().getResponse();
        assertThat(csrfWrites(failed)).isZero();
        assertThat(lastCookie(failed, KauthPref.SESSION_COOKIE_NAME) == null).isTrue();

        var success = mvc.perform(post("/api/v1/auth/otp").cookie(anonymousCsrf)
                        .contentType("application/json").content(otpBody(otpToken, code)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.step").value("success"))
                .andReturn().getResponse();
        var cookies = completedLoginCookies(success);
        assertThat(cookies.csrf.getValue().equals(anonymousCsrf.getValue()))
                .as("completed OTP renews the anonymous CSRF token").isFalse();
        mvc.perform(csrf(post("/api/v1/auth/logout"), cookies)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(cookies.session)).andExpect(status().isUnauthorized());
    }

    @Test void failedCredentialsPreserveExistingSessionAndCsrf() throws Exception {
        Long id = f.user(false, false);
        var cookies = login(id, OLD_PASSWORD);
        var failure = mvc.perform(csrf(post("/api/v1/auth/login"), cookies)
                        .contentType("application/json").content(loginBody(id, OTHER_PASSWORD)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andReturn().getResponse();
        assertThat(csrfWrites(failure)).isZero();
        assertThat(lastCookie(failure, KauthPref.SESSION_COOKIE_NAME) == null).isTrue();
        mvc.perform(get("/api/v1/auth/me").cookie(cookies.session, cookies.csrf)).andExpect(status().isOk());
    }

    @Test void otpChallengeWithExistingSessionPreservesItsCsrfUntilCompletion() throws Exception {
        Long existingId = f.user(false, false);
        var existing = login(existingId, OLD_PASSWORD);
        Long otpId = f.user(false, true);
        var challenge = mvc.perform(csrf(post("/api/v1/auth/login"), existing)
                        .contentType("application/json").content(loginBody(otpId, OLD_PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.step").value("otp"))
                .andReturn().getResponse();
        assertThat(csrfWrites(challenge)).isZero();
        assertThat(lastCookie(challenge, KauthPref.SESSION_COOKIE_NAME) == null).isTrue();
        mvc.perform(get("/api/v1/auth/me").cookie(existing.session, existing.csrf))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(existingId));
    }

    @Test void acceptedBearerPrecedesOtherUserCookieAndDoesNotReplaceCsrf() throws Exception {
        Long apiId = f.user(false, false);
        grantTokenPermission(apiId);
        String apiToken = createApiToken(login(apiId, OLD_PASSWORD));
        Long cookieId = f.user(false, false);
        var cookies = login(cookieId, OLD_PASSWORD);
        var read = mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + apiToken)
                        .cookie(cookies.session, cookies.csrf))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(apiId))
                .andReturn();
        assertThat(csrfWrites(read.getResponse())).isZero();
        assertThat(read.getRequest().getSession(false) == null).isTrue();

        var changed = mvc.perform(post("/api/v1/auth/password").header("Authorization", "Bearer " + apiToken)
                        .cookie(cookies.session, cookies.csrf).contentType("application/json")
                        .content(passwordBody(OLD_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(csrfWrites(changed)).isZero();
        assertThat(f.users.findById(apiId).orElseThrow().authenticationVersion()).isEqualTo(1);
        assertThat(f.users.findById(cookieId).orElseThrow().authenticationVersion()).isZero();
        mvc.perform(get("/api/v1/auth/me").cookie(cookies.session, cookies.csrf)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"invalid,missing", "invalid,empty", "invalid,mismatched", "empty,missing", "empty,empty", "empty,mismatched"})
    void bearerFallbackCsrfDenialDoesNotReachPasswordVerificationOrChangeAccess(String bearerKind, String csrfKind) throws Exception {
        Long id = f.user(false, false);
        grantTokenPermission(id);
        var cookies = login(id, OLD_PASSWORD);
        String apiToken = createApiToken(cookies);
        var verifications = new java.util.concurrent.atomic.AtomicInteger();
        f.hasher.afterVerified = verifications::incrementAndGet;
        var request = post("/api/v1/auth/password")
                .header("Authorization", bearerKind.equals("empty") ? "Bearer " : "Bearer invalid-token")
                .cookie(cookies.session, cookies.csrf).contentType("application/json")
                .content(passwordBody(OLD_PASSWORD, NEW_PASSWORD));
        if (!csrfKind.equals("missing")) request.header("X-XSRF-TOKEN", csrfKind.equals("empty") ? "" : "wrong");
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_token_invalid"));
        assertThat(verifications.get()).as("CSRF rejection occurs before password verification").isZero();
        assertThat(f.users.findById(id).orElseThrow().authenticationVersion()).isZero();
        assertThat(f.count("security_events", id, "event_type='PASSWORD_CHANGED'")).isZero();
        mvc.perform(get("/api/v1/auth/me").cookie(cookies.session, cookies.csrf)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + apiToken)).andExpect(status().isOk());
    }

    @ParameterizedTest @ValueSource(strings = {"Bearer ", "Bearer    ", "Bearer invalid-token"})
    void rejectedBearerWithValidCookieAndCsrfStillAllowsRealPasswordChange(String authorization) throws Exception {
        Long id = f.user(false, false);
        var cookies = login(id, OLD_PASSWORD);
        mvc.perform(csrf(post("/api/v1/auth/password"), cookies).header("Authorization", authorization)
                        .contentType("application/json").content(passwordBody(OLD_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());
        assertThat(f.users.findById(id).orElseThrow().authenticationVersion()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"Bearer ", "Bearer    "})
    void emptyBearerWithoutCookieRemainsUnauthorized(String authorization) throws Exception {
        mvc.perform(post("/api/v1/auth/password").header("Authorization", authorization)
                        .contentType("application/json").content(passwordBody(OLD_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void missingApiTokenInputsAreRejected(String rawToken) {
        assertThat(f.api.validateToken(rawToken)).isEmpty();
    }

    @Test void successfulLogoutClearsCsrfAndNextAnonymousLoginGetsUsablePair() throws Exception {
        Long id = f.user(false, false);
        var cookies = login(id, OLD_PASSWORD);
        var logout = mvc.perform(csrf(post("/api/v1/auth/logout"), cookies))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        Cookie cleared = lastCookie(logout, "XSRF-TOKEN");
        assertThat(cleared != null).isTrue();
        assertThat(cleared.getMaxAge()).as("successful logout expires CSRF cookie").isZero();
        assertThat(cleared.getValue().isEmpty()).isTrue();
        assertThat(lastCookie(logout, KauthPref.SESSION_COOKIE_NAME).getMaxAge()).isZero();
        mvc.perform(get("/api/v1/auth/me").cookie(cookies.session)).andExpect(status().isUnauthorized());

        var relogin = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(loginBody(id, OLD_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse();
        var fresh = completedLoginCookies(relogin);
        mvc.perform(csrf(post("/api/v1/auth/logout"), fresh)).andExpect(status().isNoContent());
    }

    @Test void failedLogoutPreservesCsrfAndSessionForRetry() throws Exception {
        Long id = f.user(false, false);
        var cookies = login(id, OLD_PASSWORD);
        f.sessions.failClose = true;
        var failed = mvc.perform(csrf(post("/api/v1/auth/logout"), cookies))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertThat(csrfWrites(failed)).isZero();
        assertThat(lastCookie(failed, KauthPref.SESSION_COOKIE_NAME) == null).isTrue();
        f.sessions.failClose = false;
        mvc.perform(get("/api/v1/auth/me").cookie(cookies.session, cookies.csrf)).andExpect(status().isOk());
        mvc.perform(csrf(post("/api/v1/auth/logout"), cookies)).andExpect(status().isNoContent());
    }

    @ParameterizedTest @CsvSource({"/api/v1/auth/password,false", "/api/v1/auth/password,true",
            "/api/v1/iam/users/me/password,false", "/api/v1/iam/users/me/password,true"})
    void passwordChangeAllowsExplicitReloginWithStaleSessionAndExistingCsrf(String path, boolean forced) throws Exception {
        Long id = f.user(forced, false);
        var cookies = login(id, OLD_PASSWORD);
        mvc.perform(csrf(post(path), cookies).contentType("application/json")
                        .content(passwordBody(OLD_PASSWORD, NEW_PASSWORD))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(cookies.session, cookies.csrf)).andExpect(status().isUnauthorized());
        var response = mvc.perform(csrf(post("/api/v1/auth/login"), cookies)
                        .contentType("application/json").content(loginBody(id, NEW_PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.step").value("success"))
                .andReturn().getResponse();
        var fresh = completedLoginCookies(response);
        assertThat(fresh.csrf.getValue().equals(cookies.csrf.getValue())).isFalse();
        mvc.perform(get("/api/v1/auth/me").cookie(fresh.session, fresh.csrf))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.forcePasswordChange").value(false));
        mvc.perform(csrf(post("/api/v1/auth/logout"), fresh)).andExpect(status().isNoContent());
    }

    private Cookie anonymousCsrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andReturn().getResponse();
        Cookie token = lastCookie(response, "XSRF-TOKEN");
        assertThat(token != null).isTrue();
        return token;
    }

    private static BrowserCookies completedLoginCookies(MockHttpServletResponse response) {
        Cookie session = lastCookie(response, KauthPref.SESSION_COOKIE_NAME);
        Cookie csrf = lastCookie(response, "XSRF-TOKEN");
        assertThat(session != null).as("successful authentication issues session cookie").isTrue();
        assertThat(csrf != null).as("successful authentication issues usable CSRF cookie").isTrue();
        assertThat(csrf.getValue().isEmpty()).isFalse();
        assertThat(lastCookie(response, "JSESSIONID") == null).isTrue();
        return new BrowserCookies(session, csrf);
    }

    private static Cookie lastCookie(MockHttpServletResponse response, String name) {
        Cookie found = null;
        for (Cookie cookie : response.getCookies()) if (cookie.getName().equals(name)) found = cookie;
        return found;
    }

    private static long csrfWrites(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream().filter(value -> value.startsWith("XSRF-TOKEN=")).count();
    }

    private String otpBody(String token, String code) {
        return f.mapper.writeValueAsString(Map.of("otpToken", token, "code", code, "deviceInfo", "test"));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void filterRejectsCredentialWhenLaterUserReadHasANewerGeneration(boolean bearer) throws Exception {
        Long id=f.user(false,false);grantTokenPermission(id);
        var cookies=login(id,OLD_PASSWORD);String apiToken=createApiToken(cookies);
        var captured=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        Runnable pause=() -> {requireTransaction();captured.countDown();AuthenticationGenerationConcurrencyTest.await(release);};
        if(bearer)f.tokens.afterHashProof=ignored -> pause.run();else f.sessions.afterHashProof=ignored -> pause.run();
        try {
            var pending=executor.submit(() -> {
                var request=get("/api/v1/auth/me");
                if(bearer)request.header("Authorization","Bearer "+apiToken);else request.cookie(cookies.session);
                return mvc.perform(request).andReturn().getResponse().getStatus();
            });
            assertThat(captured.await(10,TimeUnit.SECONDS)).isTrue();
            f.userService.changePassword(id,0,OLD_PASSWORD,NEW_PASSWORD);
            release.countDown();
            assertThat(pending.get(20,TimeUnit.SECONDS)).isEqualTo(401);
        } finally {release.countDown();AuthenticationGenerationConcurrencyTest.stop(executor);}
    }

    private void grantTokenPermission(Long id){
        f.jdbc.sql("insert into md_user_permissions(user_id,form_code,action) values (:id,'iam.profile','manage_tokens')")
                .param("id",id).update();
        f.permissions.recalculateEffectivePermissions(id);
    }

    private BrowserCookies login(Long id,String password) throws Exception {
        var response=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(loginBody(id,password)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.step").value("success")).andReturn().getResponse();
        assertThat(response.getContentAsString().contains("authenticationVersion")).isFalse();
        Cookie session=response.getCookie(KauthPref.SESSION_COOKIE_NAME);
        assertThat(session!=null).isTrue();
        // Obtain the real double-submit cookie from the production CSRF filter.
        var handshake=mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk()).andReturn().getResponse();
        Cookie csrf=handshake.getCookie("XSRF-TOKEN");assertThat(csrf!=null).isTrue();
        assertThat(handshake.getContentAsString().contains("authenticationVersion")).isFalse();
        return new BrowserCookies(session,csrf);
    }

    private String createApiToken(BrowserCookies cookies) throws Exception {
        var response=mvc.perform(csrf(post("/api/v1/iam/profile/tokens"),cookies)
                        .contentType("application/json").content("{\"name\":\"http-test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        assertThat(response.getContentAsString().contains("authenticationVersion")).isFalse();
        assertThat(response.getContentAsString().contains("apiTokenId")).isFalse();
        return f.mapper.readTree(response.getContentAsString()).get("rawSecretToken").asText();
    }

    private String loginBody(Long id,String password){
        return f.mapper.writeValueAsString(Map.of("login",f.users.findById(id).orElseThrow().login(),"password",password,"deviceInfo","test"));
    }
    private String passwordBody(String oldPassword,String newPassword){return f.mapper.writeValueAsString(Map.of("oldPassword",oldPassword,"newPassword",newPassword));}
    private static MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request,BrowserCookies cookies){
        return request.cookie(cookies.session,cookies.csrf).header("X-XSRF-TOKEN",cookies.csrf.getValue());
    }
    private static final class BrowserCookies {
        final Cookie session;final Cookie csrf;
        BrowserCookies(Cookie session,Cookie csrf){this.session=session;this.csrf=csrf;}
    }

    @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableTransactionManagement
    @Import({SecurityConfig.class,WebMvcConfig.class,ProblemDetailAuthHandlers.class,
            KauthAuthenticationFilter.class,RequiresPermissionInterceptor.class,RateLimitFilter.class,RateLimitService.class,
            SearchPolicyProvider.class,
            com.greenwhite.dwh.instance.search.repository.SearchSettingsRepository.class,
            IdempotencyFilter.class,IdempotencyService.class,IdempotencyRepository.class,GlobalExceptionHandler.class,
            KauthAuthController.class,KauthPasswordController.class,KauthApiTokenController.class,KauthChannelController.class})
    static class HttpConfiguration {}
}
