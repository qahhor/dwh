package com.smartup24.cms.instance.kauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.security.SecurityContext.KauthPrincipal;
import com.smartup24.cms.instance.kauth.service.KauthCredentialGuard;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdRoleRepository.PermissionPair;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.ms.notify.sse.MsSseAccess;
import com.smartup24.cms.instance.ms.notify.sse.MsSseController;
import com.smartup24.cms.instance.ms.notify.sse.MsSseRegistry;
import com.smartup24.cms.instance.support.TestDatabases;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** An already-open HTTP response must stop carrying private data when its original proof or rights expire. */
class MsSseRevocationIntegrationTest {
    static AuthenticationGenerationFixture f;
    MsSseRegistry streams;
    MockMvc mvc;
    long userId;

    @BeforeAll
    static void setUp() {
        f = new AuthenticationGenerationFixture(TestDatabases.migratedCopy("smc_sse_revocation"));
    }

    @AfterAll
    static void tearDown() {
        f.close();
    }

    @BeforeEach
    void openRegistry() {
        streams = new MsSseRegistry(
                60_000,
                5,
                new MsSseAccess(
                        new KauthCredentialGuard(f.sessions, f.tokens),
                        f.permissions,
                        f.context.getBean(MdUserService.class)));
        mvc = MockMvcBuilders.standaloneSetup(new MsSseController(streams)).build();
        userId = f.user(false, false);
        permissions(true);
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revokingOneProofClosesOnlyItsStream(boolean bearer) throws Exception {
        var revoked = principal(bearer);
        var live = principal(bearer);
        var first = open(revoked);
        var second = open(live);
        send("before-revocation");
        assertThat(body(first)).contains("before-revocation");
        if (bearer) f.tokens.revoke(revoked.apiTokenId(), userId);
        else f.context.getBean(KauthSessionService.class).closeUserSession(userId, revoked.sessionId());

        send("after-revocation");

        assertThat(body(first)).doesNotContain("after-revocation");
        assertThat(body(second)).contains("after-revocation");
        assertThat(streams.openConnectionCount()).isEqualTo(1);
    }

    @Test
    void logoutClosesTheOriginalStream() throws Exception {
        var proof = principal(false);
        var response = open(proof);
        f.context.getBean(KauthSessionService.class).closeSession(proof.sessionId());

        send("after-logout");

        assertThat(body(response)).doesNotContain("after-logout");
        assertThat(streams.openConnectionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockingThenUnblockingDoesNotReviveAnOpenProof(boolean bearer) throws Exception {
        var response = open(principal(bearer));
        f.blockAndUnblock(userId);

        send("after-block");

        assertThat(body(response)).doesNotContain("after-block");
        assertThat(streams.openConnectionCount()).isZero();
    }

    @Test
    void currentPermissionIsCheckedEvenWithoutAnInvalidationNotice() throws Exception {
        var response = open(principal(false));
        permissions(false);

        send("after-rights-revocation");

        assertThat(body(response)).doesNotContain("after-rights-revocation");
        assertThat(streams.openConnectionCount()).isZero();
        permissions(true);
        send("after-regrant");
        assertThat(body(response)).doesNotContain("after-regrant");
    }

    @Test
    void heartbeatClosesRevokedConnectionsWhileNoNotificationsArrive() throws Exception {
        var proof = principal(false);
        var response = open(proof);
        f.context.getBean(KauthSessionService.class).closeSession(proof.sessionId());

        streams.sendHeartbeat();

        assertThat(body(response)).doesNotContain("ping");
        assertThat(streams.openConnectionCount()).isZero();
    }

    @Test
    void anIdleExpiredSessionCannotKeepReceivingData() throws Exception {
        var response = open(principal(false));
        f.clock.advance(Duration.ofHours(25));

        send("after-session-expiry");

        assertThat(body(response)).doesNotContain("after-session-expiry");
        assertThat(streams.openConnectionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anOldAuthenticationVersionCannotReceiveWithAnOtherwiseOpenProof(boolean bearer) throws Exception {
        var response = open(principal(bearer));
        f.users.incrementAuthenticationVersion(userId);

        send("after-generation-change");

        assertThat(body(response)).doesNotContain("after-generation-change");
        assertThat(streams.openConnectionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aBlockedAccountCannotReceiveWithAnOtherwiseOpenProof(boolean bearer) throws Exception {
        var response = open(principal(bearer));
        f.jdbc.sql("update md_users set state = 'P' where id = :id")
                .param("id", userId)
                .update();

        send("while-blocked");

        assertThat(body(response)).doesNotContain("while-blocked");
        assertThat(streams.openConnectionCount()).isZero();
    }

    @Test
    void anExpiredApiTokenCannotKeepReceivingData() throws Exception {
        var proof = principal(true);
        var response = open(proof);
        f.jdbc.sql("update kauth_api_tokens set expires_at = now() - interval '1 second' where id = :id")
                .param("id", proof.apiTokenId())
                .update();

        send("after-token-expiry");

        assertThat(body(response)).doesNotContain("after-token-expiry");
        assertThat(streams.openConnectionCount()).isZero();
    }

    @Test
    void aRequiredPasswordChangeStopsTheStream() throws Exception {
        var response = open(principal(false));
        f.jdbc.sql("update md_users set force_password_change = true where id = :id")
                .param("id", userId)
                .update();

        send("after-forced-change");

        assertThat(body(response)).doesNotContain("after-forced-change");
        assertThat(streams.openConnectionCount()).isZero();
    }

    private KauthPrincipal principal(boolean bearer) {
        var proof = f.principal(userId, bearer);
        return new KauthPrincipal(
                proof.userId(),
                proof.login(),
                proof.email(),
                proof.sessionId(),
                proof.isApi(),
                Set.of("notify.inbox.view"),
                f.permissions.getPermissionVersion(userId),
                false,
                proof.authenticationVersion(),
                proof.apiTokenId());
    }

    private MvcResult open(KauthPrincipal proof) throws Exception {
        SecurityContext.setPrincipal(proof);
        try {
            return mvc.perform(get("/api/v1/events"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
        } finally {
            SecurityContext.clear();
        }
    }

    private void send(String title) {
        streams.send(userId, "notification", Map.of("title", title, "body", "private-body"));
    }

    private static String body(MvcResult response) throws Exception {
        return response.getResponse().getContentAsString();
    }

    private void permissions(boolean allowed) {
        var repository = new MdPermissionRepository(f.jdbc);
        repository.replaceUserPermissions(
                userId, allowed ? List.of(new PermissionPair("notify.inbox", "view")) : List.of());
        repository.recalculateEffectivePermissions(userId);
    }
}
