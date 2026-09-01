package com.greenwhite.dwh.cp.deployment;

import com.greenwhite.dwh.cp.error.CpApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "dwh.schema-gate.enabled=false",
        "dwh.cp.admin-login=",
        "dwh.cp.admin-email=",
        "dwh.cp.admin-password="
})
@Testcontainers
class CpDeploymentRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CpDeploymentRepository repository;

    @Autowired
    private CpDeploymentService service;

    @Autowired
    private JdbcClient jdbc;

    private long instanceId;
    private UUID releaseId;
    private UUID previousReleaseId;

    @BeforeEach
    void resetData() {
        jdbc.sql("truncate table cp_clients, cp_releases, cp_audit_events restart identity cascade")
                .update();
        instanceId = createInstance();
        releaseId = createRelease("2026.9.11", 'a');
        previousReleaseId = createRelease("2026.9.10", 'b');
    }

    @Test
    void createsOneRequestedDeploymentAndAcceptsOnlyByteExactEventReplay() {
        UUID first = repository.createRequested(instanceId, releaseId, 7, previousReleaseId);
        UUID second = repository.createRequested(instanceId, releaseId, 7, previousReleaseId);

        assertThat(second).isEqualTo(first);
        CpDeployment stored = repository.findByInstanceAndGeneration(instanceId, 7).orElseThrow();
        assertThat(stored.id()).isEqualTo(first);
        assertThat(stored.status()).isEqualTo(CpDeploymentStatus.REQUESTED);
        assertThat(stored.releaseId()).isEqualTo(releaseId);
        assertThat(stored.previousReleaseId()).isEqualTo(previousReleaseId);
        assertThat(stored.createdAt()).isNotNull();

        assertThat(repository.appendEvent(
                first, 1, "request:7", CpDeploymentStatus.REQUESTED, null, null)).isTrue();
        assertThat(repository.appendEvent(
                first, 1, "request:7", CpDeploymentStatus.REQUESTED, null, null)).isFalse();

        assertEventConflict(() -> repository.appendEvent(
                first, 1, "request:7", CpDeploymentStatus.PREFLIGHT, null, null));
        assertEventConflict(() -> repository.appendEvent(
                first, 2, "request:7", CpDeploymentStatus.REQUESTED, null, null));
        assertThat(eventCount(first)).isOne();

        UUID anotherReleaseId = createRelease("2026.9.12", 'e');
        assertApiError(() -> repository.createRequested(
                instanceId, anotherReleaseId, 7, previousReleaseId),
                "deployment_request_conflict");
        assertApiError(() -> repository.createRequested(
                instanceId, releaseId, 7, null),
                "deployment_request_conflict");
    }

    @Test
    void concurrentExactRequestsProduceOneDeploymentAndOneInitialEvent() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> requestAfterBarrier(ready, start));
            var second = executor.submit(() -> requestAfterBarrier(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).id());
        }

        assertThat(jdbc.sql("select count(*) from cp_deployments")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("select count(*) from cp_deployment_events")
                .query(Long.class).single()).isOne();
    }

    @Test
    void transitionIsSequencedIdempotentAndRollsBackInvalidEvents() {
        CpDeployment deployment = service.request(
                instanceId, releaseId, 21, previousReleaseId);

        CpDeployment preflight = service.transition(
                deployment.id(), 2, "event:preflight",
                CpDeploymentStatus.PREFLIGHT, null, "preflight started");
        assertThat(preflight.status()).isEqualTo(CpDeploymentStatus.PREFLIGHT);
        assertThat(preflight.startedAt()).isNotNull();

        CpDeployment replay = service.transition(
                deployment.id(), 2, "event:preflight",
                CpDeploymentStatus.PREFLIGHT, null, "preflight started");
        assertThat(replay.status()).isEqualTo(CpDeploymentStatus.PREFLIGHT);
        assertThat(eventCount(deployment.id())).isEqualTo(2);

        assertThatThrownBy(() -> service.transition(
                deployment.id(), 3, "event:skip",
                CpDeploymentStatus.DEPLOYING, "unsafe_skip", null))
                .isInstanceOfSatisfying(CpDeploymentTransitionException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo("deployment_transition_invalid"));
        assertThat(repository.findByInstanceAndGeneration(instanceId, 21).orElseThrow().status())
                .isEqualTo(CpDeploymentStatus.PREFLIGHT);
        assertThat(eventCount(deployment.id())).isEqualTo(2);

        assertApiError(() -> service.transition(
                deployment.id(), 4, "event:gap",
                CpDeploymentStatus.BACKUP_VERIFIED, null, null),
                "deployment_event_sequence_invalid");
        assertThat(eventCount(deployment.id())).isEqualTo(2);

        assertThat(service.transition(
                deployment.id(), 3, "event:backup",
                CpDeploymentStatus.BACKUP_VERIFIED, null, null).status())
                .isEqualTo(CpDeploymentStatus.BACKUP_VERIFIED);
    }

    @Test
    void rollbackWithoutPreviousReleaseLeavesStatusAndEventsUnchanged() {
        CpDeployment deployment = service.request(instanceId, releaseId, 31, null);
        service.transition(deployment.id(), 2, "event:31:preflight",
                CpDeploymentStatus.PREFLIGHT, null, null);
        service.transition(deployment.id(), 3, "event:31:backup",
                CpDeploymentStatus.BACKUP_VERIFIED, null, null);
        service.transition(deployment.id(), 4, "event:31:migrating",
                CpDeploymentStatus.MIGRATING, null, null);

        assertThatThrownBy(() -> service.transition(
                deployment.id(), 5, "event:31:rollback",
                CpDeploymentStatus.ROLLING_BACK, "migration_failed", null))
                .isInstanceOf(CpDeploymentTransitionException.class);

        assertThat(repository.findByInstanceAndGeneration(instanceId, 31).orElseThrow().status())
                .isEqualTo(CpDeploymentStatus.MIGRATING);
        assertThat(eventCount(deployment.id())).isEqualTo(4);
    }

    @Test
    void terminalTransitionSetsFinishedTimestampWithoutInventingAStartTimestamp() {
        CpDeployment deployment = service.request(
                instanceId, releaseId, 41, previousReleaseId);

        CpDeployment cancelled = service.transition(
                deployment.id(), 2, "event:41:cancelled",
                CpDeploymentStatus.CANCELLED, "operator_cancelled", null);

        assertThat(cancelled.status()).isEqualTo(CpDeploymentStatus.CANCELLED);
        assertThat(cancelled.startedAt()).isNull();
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(cancelled.reasonCode()).isEqualTo("operator_cancelled");
        assertThat(eventCount(deployment.id())).isEqualTo(2);
    }

    private CpDeployment requestAfterBarrier(CountDownLatch ready,
                                             CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return service.request(instanceId, releaseId, 9, previousReleaseId);
    }

    private long createInstance() {
        long clientId = jdbc.sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values ('deployment-client', 'Deployment Client', 'S')
                        returning id
                        """)
                .query(Long.class)
                .single();
        return jdbc.sql("""
                        insert into cp_instances(client_id, environment, url)
                        values (:clientId, 'production', 'https://deployment.invalid')
                        returning id
                        """)
                .param("clientId", clientId)
                .query(Long.class)
                .single();
    }

    private UUID createRelease(String version, char digestSeed) {
        String digest = "sha256:" + String.valueOf(digestSeed).repeat(64);
        return jdbc.sql("""
                        insert into cp_releases(
                            version, source_commit, manifest_digest, manifest_location,
                            verification_bundle_digest, config_schema_version,
                            minimum_agent_version, deployment_modes, status,
                            created_by_identity, ready_at)
                        values (
                            :version, '0123456789abcdef0123456789abcdef01234567',
                            :manifestDigest, :manifestLocation,
                            :bundleDigest, '1', '1.0.0',
                            cast('{MANAGED_CLOUD}' as text[]), 'READY', 'test', now())
                        returning id
                        """)
                .param("version", version)
                .param("manifestDigest", digest)
                .param("manifestLocation", "https://artifacts.invalid/" + version + "/manifest.json")
                .param("bundleDigest", "sha256:" + String.valueOf((char) (digestSeed + 2)).repeat(64))
                .query(UUID.class)
                .single();
    }

    private long eventCount(UUID deploymentId) {
        return jdbc.sql("select count(*) from cp_deployment_events where deployment_id = :deploymentId")
                .param("deploymentId", deploymentId)
                .query(Long.class)
                .single();
    }

    private static void assertEventConflict(ThrowingCall call) {
        assertApiError(call, "deployment_event_conflict");
    }

    private static void assertApiError(ThrowingCall call, String errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(CpApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(errorCode));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
