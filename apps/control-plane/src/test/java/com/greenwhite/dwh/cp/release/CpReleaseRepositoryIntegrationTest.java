package com.greenwhite.dwh.cp.release;

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

import java.net.URI;
import java.util.List;
import java.util.Set;
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
class CpReleaseRepositoryIntegrationTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);

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
    private CpReleaseService service;

    @Autowired
    private CpReleaseRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private long actorUserId;

    @BeforeEach
    void resetData() {
        jdbc.sql("truncate table cp_releases, cp_users, cp_audit_events restart identity cascade").update();
        actorUserId = jdbc.sql("""
                        insert into cp_users(name, login, email, password_hash)
                        values ('Release Admin', 'release-admin', 'release-admin@example.invalid', 'hash')
                        returning id
                        """)
                .query(Long.class)
                .single();
    }

    @Test
    void persistsReadyReleaseComponentsAndSingleBuildAuditIdempotently() {
        VerifiedReleaseCommand command = validCommand("2026.9.1", SHA_A);

        UUID first = service.registerVerified(command, "github-actions:release.yml@main");
        UUID second = service.registerVerified(command, "github-actions:release.yml@main");

        assertThat(second).isEqualTo(first);
        assertThat(repository.requireById(first)).isEqualTo(
                repository.list().getFirst());
        CpRelease stored = repository.requireById(first);
        assertThat(stored.status()).isEqualTo(ReleaseStatus.READY);
        assertThat(stored.deploymentModes())
                .containsExactlyInAnyOrder(DeploymentMode.MANAGED_CLOUD, DeploymentMode.CUSTOMER_HOSTED);
        assertThat(stored.components()).extracting(ReleaseComponent::name)
                .containsExactly("instance", "postgres", "proxy", "typesense", "web");
        assertThat(jdbc.sql("select count(*) from cp_releases").query(Long.class).single()).isOne();
        assertThat(jdbc.sql("select count(*) from cp_release_components").query(Long.class).single())
                .isEqualTo(5);
        assertThat(auditCount("release.ready")).isOne();
    }

    @Test
    void sameSemverWithChangedManifestOrComponentCannotMutateReadyRows() {
        VerifiedReleaseCommand command = validCommand("2026.9.1", SHA_A);
        UUID releaseId = service.registerVerified(command, "build:one");
        String before = releaseSnapshot(releaseId);

        VerifiedReleaseCommand changedManifest = validCommand("2026.9.1", SHA_B);
        assertThatThrownBy(() -> service.registerVerified(changedManifest, "build:two"))
                .isInstanceOfSatisfying(CpApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("release_version_conflict"));

        List<ReleaseComponent> changedComponents = command.components().stream()
                .map(component -> component.name().equals("web")
                        ? new ReleaseComponent(
                                component.name(),
                                "registry.invalid/dwh/web@" + SHA_B,
                                SHA_B,
                                component.sbomDigest(),
                                component.provenanceDigest(),
                                component.minimumSchemaVersion(),
                                component.maximumRollbackSchemaVersion())
                        : component)
                .toList();
        VerifiedReleaseCommand changedComponent = new VerifiedReleaseCommand(
                command.version(), command.sourceCommit(), command.manifestDigest(),
                command.manifestLocation(), command.verificationBundleDigest(),
                command.configSchemaVersion(), command.minimumAgentVersion(),
                command.deploymentModes(), changedComponents);
        assertThatThrownBy(() -> service.registerVerified(changedComponent, "build:three"))
                .isInstanceOfSatisfying(CpApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("release_version_conflict"));

        assertThat(releaseSnapshot(releaseId)).isEqualTo(before);
        assertThat(auditCount("release.ready")).isOne();
    }

    @Test
    void revokesReadyOncePersistsBoundedReasonAndCannotReturnToReady() {
        VerifiedReleaseCommand command = validCommand("2026.9.1", SHA_A);
        UUID releaseId = service.registerVerified(command, "build:one");

        CpRelease revoked = service.revoke(releaseId, "critical CVE", actorUserId);
        CpRelease repeated = service.revoke(releaseId, "critical CVE", actorUserId);

        assertThat(revoked.status()).isEqualTo(ReleaseStatus.REVOKED);
        assertThat(repeated.status()).isEqualTo(ReleaseStatus.REVOKED);
        assertThat(auditCount("release.revoked")).isOne();
        assertThat(jdbc.sql("""
                        select details ->> 'reason'
                        from cp_audit_events
                        where action = 'release.revoked'
                        """).query(String.class).single()).isEqualTo("critical CVE");
        assertThatThrownBy(() -> service.registerVerified(command, "build:retry"))
                .isInstanceOfSatisfying(CpApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("release_revoked"));
        assertThat(repository.requireById(releaseId).status()).isEqualTo(ReleaseStatus.REVOKED);
    }

    @Test
    void rollsBackReleaseAndComponentsWhenAuditInsertFails() {
        jdbc.sql("""
                        create or replace function cp_test_reject_release_audit()
                        returns trigger language plpgsql as $$
                        begin
                            if new.action = 'release.ready' then
                                raise exception 'release audit rejected by test';
                            end if;
                            return new;
                        end
                        $$
                        """).update();
        jdbc.sql("""
                        create trigger cp_test_reject_release_audit_trigger
                        before insert on cp_audit_events
                        for each row execute function cp_test_reject_release_audit()
                        """).update();
        try {
            assertThatThrownBy(() -> service.registerVerified(
                    validCommand("2026.9.1", SHA_A), "build:broken"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.sql("drop trigger cp_test_reject_release_audit_trigger on cp_audit_events").update();
            jdbc.sql("drop function cp_test_reject_release_audit()").update();
        }

        assertThat(jdbc.sql("select count(*) from cp_releases").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from cp_release_components").query(Long.class).single())
                .isZero();
    }

    @Test
    void concurrentExactRegistrationProducesOneReleaseAndOneAudit() throws Exception {
        VerifiedReleaseCommand command = validCommand("2026.9.1", SHA_A);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> registerAfterBarrier(command, ready, start));
            var second = executor.submit(() -> registerAfterBarrier(command, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS))
                    .isEqualTo(second.get(10, TimeUnit.SECONDS));
        }

        assertThat(jdbc.sql("select count(*) from cp_releases").query(Long.class).single()).isOne();
        assertThat(jdbc.sql("select count(*) from cp_release_components").query(Long.class).single())
                .isEqualTo(5);
        assertThat(auditCount("release.ready")).isOne();
    }

    private UUID registerAfterBarrier(VerifiedReleaseCommand command,
                                      CountDownLatch ready,
                                      CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return service.registerVerified(command, "build:concurrent");
    }

    private long auditCount(String action) {
        return jdbc.sql("select count(*) from cp_audit_events where action = :action")
                .param("action", action)
                .query(Long.class)
                .single();
    }

    private String releaseSnapshot(UUID releaseId) {
        return jdbc.sql("""
                        select row_to_json(snapshot)::text
                        from (
                            select release.version, release.source_commit,
                                   release.manifest_digest, release.manifest_location,
                                   release.verification_bundle_digest,
                                   release.config_schema_version,
                                   release.minimum_agent_version,
                                   release.deployment_modes, release.status,
                                   (select jsonb_agg(to_jsonb(component) order by component.component_name)
                                    from cp_release_components component
                                    where component.release_id = release.id) as components
                            from cp_releases release
                            where release.id = :releaseId
                        ) snapshot
                        """)
                .param("releaseId", releaseId)
                .query(String.class)
                .single();
    }

    private static VerifiedReleaseCommand validCommand(String version, String manifestDigest) {
        return new VerifiedReleaseCommand(
                version,
                "0123456789abcdef0123456789abcdef01234567",
                manifestDigest,
                URI.create("https://artifacts.invalid/releases/" + version + "/manifest.json"),
                SHA_B,
                "1",
                "1.0.0",
                Set.of(DeploymentMode.MANAGED_CLOUD, DeploymentMode.CUSTOMER_HOSTED),
                List.of(
                        component("instance"),
                        component("web"),
                        component("postgres"),
                        component("typesense"),
                        component("proxy")));
    }

    private static ReleaseComponent component(String name) {
        return new ReleaseComponent(
                name,
                "registry.invalid/dwh/" + name + "@" + SHA_A,
                SHA_A,
                SHA_B,
                SHA_A,
                "006",
                "006");
    }
}
