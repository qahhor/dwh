package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.audit.CpAuditRepository;
import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.support.CpPostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CpInstanceCredentialRepositoryIntegrationTest extends CpPostgresIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String ENROLLMENT_HASH =
            "d3f9ce1515a870799f28b41da5243160d3e2b889cc4d7ac3507bc8bcef9694b8";
    private static final String CREDENTIAL_HASH =
            "169fe0b19d7947581c87df4323b779c31905007039d07179aeac0ca342f40ba1";
    private static final String ROTATED_CREDENTIAL_HASH =
            "d36af989267de568a895efd82e2364d82a45c6c6bd43ff1d6deaf4c3d131fdf3";
    private static final String EXPIRED_CREDENTIAL_HASH =
            "a88d2e65cf288c1f05885ed91fe1b7ad5fa55d32f90c401e6bd5b8937971cc0b";

    private long actorUserId;
    private long clientAId;
    private long clientBId;
    private long instanceAId;
    private long instanceBId;
    private CpInstanceCredentialRepository repository;
    private CpAuditRepository auditRepository;
    private CpTokenGenerator tokenGenerator;
    private CpInstanceCredentialService service;

    @BeforeEach
    void setUp() {
        cleanAndMigrateTo("6");
        actorUserId = insertUser();
        clientAId = insertClient("client-a");
        clientBId = insertClient("client-b");
        instanceAId = insertInstance(clientAId, "https://client-a.invalid");
        instanceBId = insertInstance(clientBId, "https://client-b.invalid");
        repository = new CpInstanceCredentialRepository(jdbc());
        auditRepository = new CpAuditRepository(jdbc());
        tokenGenerator = mock(CpTokenGenerator.class);
        service = new CpInstanceCredentialService(
                repository,
                auditRepository,
                tokenGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesEnrollmentAsHashAndAuditsTheIssue() {
        String rawEnrollment = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        when(tokenGenerator.generate()).thenReturn(rawEnrollment);

        var issued = service.issueEnrollment(instanceAId, actorUserId);

        assertThat(issued.enrollmentToken()).isEqualTo(rawEnrollment);
        assertThat(jdbc().sql("""
                        select rtrim(token_hash)
                        from cp_instance_enrollment_tokens
                        where instance_id = :instanceId
                        """)
                .param("instanceId", instanceAId)
                .query(String.class)
                .single()).isEqualTo(
                        "0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a");
        assertThat(countRawTokenOccurrences(rawEnrollment)).isZero();
        assertThat(auditCount("instance.enrollment_issued")).isEqualTo(1L);
    }

    @Test
    void exchangesTheSameEnrollmentExactlyOnceUnderConcurrency() throws Exception {
        jdbc().sql("""
                        insert into cp_instance_enrollment_tokens(
                            instance_id, token_hash, expires_at, created_by)
                        values (:instanceId, :tokenHash, :expiresAt, :actorUserId)
                        """)
                .param("instanceId", instanceAId)
                .param("tokenHash", ENROLLMENT_HASH)
                .param("expiresAt", dbTime(NOW.plusSeconds(900)))
                .param("actorUserId", actorUserId)
                .update();
        when(tokenGenerator.generate()).thenReturn("credential-raw");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> exchangeAfterBarrier(ready, start));
            Future<Object> second = executor.submit(() -> exchangeAfterBarrier(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes)
                    .filteredOn(value -> value instanceof CpInstanceCredentialService.IssuedCredential)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(value -> value instanceof CpApiException error
                            && error.status().value() == 401
                            && error.errorCode().equals("instance_enrollment_invalid"))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc().sql("""
                        select count(*)
                        from cp_instance_credentials
                        where instance_id = :instanceId and revoked_at is null
                        """)
                .param("instanceId", instanceAId)
                .query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(jdbc().sql("""
                        select rtrim(credential_hash)
                        from cp_instance_credentials
                        where instance_id = :instanceId
                        """)
                .param("instanceId", instanceAId)
                .query(String.class)
                .single()).isEqualTo(CREDENTIAL_HASH);
        assertThat(countRawTokenOccurrences("credential-raw")).isZero();
        assertThat(jdbc().sql("""
                        select consumed_at is not null
                        from cp_instance_enrollment_tokens
                        where token_hash = :tokenHash
                        """)
                .param("tokenHash", ENROLLMENT_HASH)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(auditCount("instance.credential_issued")).isEqualTo(1L);
    }

    @Test
    void authenticatesScopedPrincipalTouchesLastUsedAndRejectsRevokedOrExpiredCredentials() {
        long credentialId = insertCredential(instanceAId, CREDENTIAL_HASH, NOW.minusSeconds(60));
        insertCredential(
                instanceBId,
                "501bf8cd4fb25886fd5c31540c653c2774b09b9574db0a27645b37585245fbb9",
                NOW.minusSeconds(60));

        var principal = service.authenticate("credential-raw").orElseThrow();

        assertThat(principal).isEqualTo(
                new CpInstancePrincipal(instanceAId, clientAId, "client-a", credentialId));
        CredentialTimes times = credentialTimes(credentialId);
        assertThat(times.activatedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(times.lastUsedAt()).isEqualTo(NOW);

        jdbc().sql("""
                        update cp_instance_credentials
                        set revoked_at = :now
                        where id = :credentialId
                        """)
                .param("now", dbTime(NOW))
                .param("credentialId", credentialId)
                .update();
        assertThat(service.authenticate("credential-raw")).isEmpty();

        long expiredId = jdbc().sql("""
                        insert into cp_instance_credentials(
                            instance_id, credential_hash, activated_at, expires_at)
                        values (:instanceId, :credentialHash, :activatedAt, :expiresAt)
                        returning id
                        """)
                .param("instanceId", instanceAId)
                .param("credentialHash", EXPIRED_CREDENTIAL_HASH)
                .param("activatedAt", dbTime(NOW.minusSeconds(120)))
                .param("expiresAt", dbTime(NOW.minusSeconds(1)))
                .query(Long.class)
                .single();
        assertThat(expiredId).isPositive();
        assertThat(service.authenticate("expired-credential")).isEmpty();
    }

    @Test
    void rotationCreatesSuccessorAndCapsPredecessorAtTwentyFourHours() {
        long predecessorId = insertCredential(instanceAId, CREDENTIAL_HASH, NOW.minusSeconds(60));
        when(tokenGenerator.generate()).thenReturn("rotate-credential");
        var principal = new CpInstancePrincipal(instanceAId, clientAId, "client-a", predecessorId);

        var rotated = service.rotate(principal);

        Instant previousValidUntil = Instant.parse("2026-09-02T00:00:00Z");
        assertThat(rotated.previousValidUntil()).isEqualTo(previousValidUntil);
        RotationState state = jdbc().sql("""
                        select old.expires_at,
                               old.successor_id,
                               successor.predecessor_id,
                               rtrim(successor.credential_hash) as successor_hash
                        from cp_instance_credentials old
                        join cp_instance_credentials successor on successor.id = old.successor_id
                        where old.id = :predecessorId
                        """)
                .param("predecessorId", predecessorId)
                .query((rs, rowNum) -> new RotationState(
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getLong("successor_id"),
                        rs.getLong("predecessor_id"),
                        rs.getString("successor_hash")))
                .single();
        assertThat(state.expiresAt()).isEqualTo(previousValidUntil);
        assertThat(state.predecessorId()).isEqualTo(predecessorId);
        assertThat(state.successorHash()).isEqualTo(ROTATED_CREDENTIAL_HASH);
        assertThat(repository.authenticate(CREDENTIAL_HASH, previousValidUntil.plusSeconds(1))).isEmpty();
        assertThat(repository.authenticate(ROTATED_CREDENTIAL_HASH, NOW))
                .contains(new CpInstancePrincipal(
                        instanceAId,
                        clientAId,
                        "client-a",
                        state.successorId()));
    }

    @Test
    void revokeIsScopedByBothInstanceAndCredentialId() {
        long credentialId = insertCredential(instanceAId, CREDENTIAL_HASH, NOW.minusSeconds(60));

        assertThatThrownBy(() -> service.revoke(instanceBId, credentialId, actorUserId))
                .isInstanceOf(CpApiException.class);
        assertThat(credentialTimes(credentialId).revokedAt()).isNull();

        service.revoke(instanceAId, credentialId, actorUserId);

        assertThat(credentialTimes(credentialId).revokedAt()).isEqualTo(NOW);
        assertThat(auditCount("instance.credential_revoked")).isEqualTo(1L);
    }

    private Object exchangeAfterBarrier(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return service.exchange("enroll-raw");
        } catch (CpApiException error) {
            return error;
        }
    }

    private long insertUser() {
        return jdbc().sql("""
                        insert into cp_users(name, login, email, password_hash)
                        values ('Fleet Operator', 'fleet-operator', 'fleet@example.invalid', 'hash')
                        returning id
                        """)
                .query(Long.class)
                .single();
    }

    private long insertClient(String code) {
        return jdbc().sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values (:code, :name, 'S')
                        returning id
                        """)
                .param("code", code)
                .param("name", code)
                .query(Long.class)
                .single();
    }

    private long insertInstance(long clientId, String url) {
        return jdbc().sql("""
                        insert into cp_instances(client_id, environment, url)
                        values (:clientId, 'production', :url)
                        returning id
                        """)
                .param("clientId", clientId)
                .param("url", url)
                .query(Long.class)
                .single();
    }

    private long insertCredential(long instanceId, String credentialHash, Instant activatedAt) {
        return jdbc().sql("""
                        insert into cp_instance_credentials(
                            instance_id, credential_hash, activated_at)
                        values (:instanceId, :credentialHash, :activatedAt)
                        returning id
                        """)
                .param("instanceId", instanceId)
                .param("credentialHash", credentialHash)
                .param("activatedAt", dbTime(activatedAt))
                .query(Long.class)
                .single();
    }

    private CredentialTimes credentialTimes(long credentialId) {
        return jdbc().sql("""
                        select activated_at, expires_at, revoked_at, last_used_at
                        from cp_instance_credentials
                        where id = :credentialId
                        """)
                .param("credentialId", credentialId)
                .query((rs, rowNum) -> new CredentialTimes(
                        rs.getTimestamp("activated_at").toInstant(),
                        rs.getTimestamp("expires_at") == null
                                ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null
                                ? null : rs.getTimestamp("revoked_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null
                                ? null : rs.getTimestamp("last_used_at").toInstant()))
                .single();
    }

    private long countRawTokenOccurrences(String rawToken) {
        return jdbc().sql("""
                        select
                            (select count(*) from cp_instance_enrollment_tokens
                             where token_hash = :rawToken)
                          + (select count(*) from cp_instance_credentials
                             where credential_hash = :rawToken)
                        """)
                .param("rawToken", rawToken)
                .query(Long.class)
                .single();
    }

    private long auditCount(String action) {
        return jdbc().sql("select count(*) from cp_audit_events where action = :action")
                .param("action", action)
                .query(Long.class)
                .single();
    }

    private static OffsetDateTime dbTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record CredentialTimes(
            Instant activatedAt,
            Instant expiresAt,
            Instant revokedAt,
            Instant lastUsedAt) {
    }

    private record RotationState(
            Instant expiresAt,
            long successorId,
            long predecessorId,
            String successorHash) {
    }
}
