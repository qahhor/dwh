package com.greenwhite.dwh.cp.deployment;

import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.instance.CpInstanceApiController;
import com.greenwhite.dwh.cp.instance.CpInstancePrincipal;
import com.greenwhite.dwh.cp.instance.api.CpDesiredStateResponse;
import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.release.DeploymentMode;
import com.greenwhite.dwh.cp.release.ReleaseComponent;
import com.greenwhite.dwh.cp.release.VerifiedReleaseCommand;
import com.greenwhite.dwh.cp.release.CpReleaseService;
import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import com.greenwhite.dwh.cp.security.CpRequiresRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "dwh.schema-gate.enabled=false",
        "dwh.cp.admin-login=",
        "dwh.cp.admin-email=",
        "dwh.cp.admin-password="
})
@AutoConfigureMockMvc
@Testcontainers
class CpTargetServiceIntegrationTest {

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
    private CpTargetService service;

    @Autowired
    private CpReleaseService releaseService;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void resetData() {
        jdbc.sql("truncate table cp_clients, cp_users, cp_releases, cp_audit_events restart identity cascade")
                .update();
    }

    @Test
    void assignsMonotonicGenerationsAndPersistsTheCompleteTargetAtomically() {
        long actorId = createUser("target-admin", CpPref.ROLE_ADMIN);
        long instanceId = createInstance("alpha", DeploymentMode.MANAGED_CLOUD);
        UUID releaseId = createReadyRelease("2026.9.1", Set.of(DeploymentMode.MANAGED_CLOUD));

        CpTarget first = service.assign(instanceId, command(releaseId, "config-1"), actorId);
        CpTarget second = service.assign(instanceId, command(releaseId, "config-2"), actorId);

        assertThat(first.generation()).isEqualTo(1);
        assertThat(second.generation()).isEqualTo(2);
        assertThat(second.instanceId()).isEqualTo(instanceId);
        assertThat(second.releaseId()).isEqualTo(releaseId);
        assertThat(second.releaseVersion()).isEqualTo("2026.9.1");
        assertThat(second.manifestDigest()).isEqualTo(manifestDigest("2026.9.1"));
        assertThat(second.manifestLocation()).isEqualTo(
                URI.create("https://artifacts.invalid/releases/2026.9.1/manifest.json"));
        assertThat(second.configVersion()).isEqualTo("config-2");
        assertThat(second.ring()).isEqualTo(RolloutRing.R1);
        assertThat(second.maintenanceWindow()).isEqualTo(maintenanceWindow());
        assertThat(second.requestedBy()).isEqualTo(actorId);
        assertThat(second.requestedAt()).isNotNull();

        assertThat(jdbc.sql("select count(*) from cp_instance_targets")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("select count(*) from cp_audit_events where action = 'instance.target.assigned'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void concurrentAssignmentsReceiveDistinctGenerations() throws Exception {
        long actorId = createUser("concurrent-admin", CpPref.ROLE_ADMIN);
        long instanceId = createInstance("concurrent", DeploymentMode.MANAGED_CLOUD);
        UUID releaseId = createReadyRelease("2026.9.2", Set.of(DeploymentMode.MANAGED_CLOUD));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> assignAfterBarrier(
                    instanceId, command(releaseId, "config-a"), actorId, ready, start));
            var second = executor.submit(() -> assignAfterBarrier(
                    instanceId, command(releaseId, "config-b"), actorId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(10, TimeUnit.SECONDS).generation(),
                    second.get(10, TimeUnit.SECONDS).generation()))
                    .containsExactlyInAnyOrder(1L, 2L);
        }

        assertThat(jdbc.sql("select generation from cp_instance_targets where instance_id = :instanceId")
                .param("instanceId", instanceId)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from cp_audit_events where action = 'instance.target.assigned'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void rejectsUnknownInstanceRevokedReleaseModeMismatchAndInvalidWindowWithStableErrors() {
        long actorId = createUser("validation-admin", CpPref.ROLE_ADMIN);
        long managedId = createInstance("managed", DeploymentMode.MANAGED_CLOUD);
        UUID readyId = createReadyRelease("2026.9.3", Set.of(DeploymentMode.MANAGED_CLOUD));
        UUID revokedId = createReadyRelease("2026.9.4", Set.of(DeploymentMode.MANAGED_CLOUD));
        UUID customerHostedId = createReadyRelease(
                "2026.9.5", Set.of(DeploymentMode.CUSTOMER_HOSTED));
        releaseService.revoke(revokedId, "unsafe release", actorId);

        assertError(() -> service.assign(Long.MAX_VALUE, command(readyId, "config-1"), actorId),
                "instance_not_found");
        assertError(() -> service.assign(managedId, command(UUID.randomUUID(), "config-1"), actorId),
                "release_not_assignable");
        assertError(() -> service.assign(managedId, command(revokedId, "config-1"), actorId),
                "release_not_assignable");
        assertError(() -> service.assign(managedId, command(customerHostedId, "config-1"), actorId),
                "release_not_assignable");

        AssignTargetCommand invalidWindow = new AssignTargetCommand(
                readyId,
                "config-1",
                RolloutRing.R1,
                new MaintenanceWindow(6, 1, LocalTime.of(2, 0), 30, "UTC"));
        assertError(() -> service.assign(managedId, invalidWindow, actorId), "target_invalid");

        jdbc.sql("update cp_instances set current_generation = :generation where id = :instanceId")
                .param("generation", Long.MAX_VALUE)
                .param("instanceId", managedId)
                .update();
        assertError(() -> service.assign(managedId, command(readyId, "config-1"), actorId),
                "target_generation_exhausted");

        assertThat(jdbc.sql("select count(*) from cp_instance_targets")
                .query(Long.class).single()).isZero();
    }

    @Test
    void auditFailureRollsBackTargetAndGeneration() {
        long actorId = createUser("audit-admin", CpPref.ROLE_ADMIN);
        long instanceId = createInstance("audit", DeploymentMode.MANAGED_CLOUD);
        UUID releaseId = createReadyRelease("2026.9.8", Set.of(DeploymentMode.MANAGED_CLOUD));
        jdbc.sql("""
                        create function cp_test_reject_target_audit() returns trigger
                        language plpgsql as $$
                        begin
                            if new.action = 'instance.target.assigned' then
                                raise exception 'target audit rejected';
                            end if;
                            return new;
                        end
                        $$
                        """).update();
        jdbc.sql("""
                        create trigger cp_test_reject_target_audit_trigger
                        before insert on cp_audit_events
                        for each row execute function cp_test_reject_target_audit()
                        """).update();
        try {
            assertThatThrownBy(() -> service.assign(
                    instanceId, command(releaseId, "config-audit"), actorId))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.sql("drop trigger cp_test_reject_target_audit_trigger on cp_audit_events").update();
            jdbc.sql("drop function cp_test_reject_target_audit()").update();
        }

        assertThat(jdbc.sql("select count(*) from cp_instance_targets")
                .query(Long.class).single()).isZero();
        assertThat(service.assign(instanceId, command(releaseId, "config-after-rollback"), actorId)
                .generation()).isEqualTo(1);
    }

    @Test
    void desiredStateIsPrincipalScopedAndReturnsNoneOnlyAfterExactReconciliation() {
        long actorId = createUser("reconcile-admin", CpPref.ROLE_ADMIN);
        long instanceA = createInstance("tenant-a", DeploymentMode.MANAGED_CLOUD);
        long instanceB = createInstance("tenant-b", DeploymentMode.MANAGED_CLOUD);
        UUID releaseId = createReadyRelease("2026.9.6", Set.of(DeploymentMode.MANAGED_CLOUD));

        assertThat(service.desiredState(principal(instanceA))).isEmpty();
        CpTarget target = service.assign(instanceA, command(releaseId, "config-6"), actorId);

        assertThat(service.desiredState(principal(instanceB))).isEmpty();
        CpDesiredStateResponse pending = service.desiredState(principal(instanceA)).orElseThrow();
        assertThat(pending.generation()).isEqualTo(target.generation());
        assertThat(pending.releaseId()).isEqualTo(releaseId);
        assertThat(pending.allowedAction())
                .isEqualTo(CpDesiredStateResponse.AllowedAction.APPLY_RELEASE);

        jdbc.sql("""
                        update cp_instances
                        set current_generation = :generation,
                            current_release_id = :releaseId,
                            current_config_version = :configVersion
                        where id = :instanceId
                        """)
                .param("generation", target.generation())
                .param("releaseId", releaseId)
                .param("configVersion", target.configVersion())
                .param("instanceId", instanceA)
                .update();

        assertThat(service.desiredState(principal(instanceA)).orElseThrow().allowedAction())
                .isEqualTo(CpDesiredStateResponse.AllowedAction.NONE);

        jdbc.sql("update cp_instances set current_config_version = 'different' where id = :instanceId")
                .param("instanceId", instanceA)
                .update();
        assertThat(service.desiredState(principal(instanceA)).orElseThrow().allowedAction())
                .isEqualTo(CpDesiredStateResponse.AllowedAction.APPLY_RELEASE);
    }

    @Test
    void httpContractAuthorizesAdminAndNeverAcceptsAnInstanceIdForDesiredState() throws Exception {
        long actorId = createUser("api-admin", CpPref.ROLE_ADMIN);
        Cookie admin = createSession(actorId, "admin-session");
        long instanceA = createInstance("api-a", DeploymentMode.MANAGED_CLOUD);
        long instanceB = createInstance("api-b", DeploymentMode.MANAGED_CLOUD);
        String credentialA = createCredential(instanceA, "credential-a");
        String credentialB = createCredential(instanceB, "credential-b");
        UUID releaseId = createReadyRelease("2026.9.7", Set.of(DeploymentMode.MANAGED_CLOUD));

        mvc.perform(put("/api/v1/instances/{instanceId}/target", instanceA)
                        .with(csrf())
                        .cookie(admin)
                        .contentType("application/json")
                        .content(targetJson(releaseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.releaseId").value(releaseId.toString()));

        mvc.perform(get("/api/v1/instances/desired-state")
                        .header("X-Instance-Token", credentialB))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/instances/desired-state")
                        .header("X-Instance-Token", credentialA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.releaseId").value(releaseId.toString()))
                .andExpect(jsonPath("$.allowedAction").value("APPLY_RELEASE"));

        long engineerId = createUser("api-engineer", CpPref.ROLE_ENGINEER);
        Cookie engineer = createSession(engineerId, "engineer-session");
        mvc.perform(put("/api/v1/instances/{instanceId}/target", instanceA)
                        .with(csrf())
                        .cookie(engineer)
                        .contentType("application/json")
                        .content(targetJson(releaseId)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("select generation from cp_instance_targets where instance_id = :instanceId")
                .param("instanceId", instanceA)
                .query(Long.class).single()).isEqualTo(1);

        Method assign = Arrays.stream(CpTargetController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PutMapping.class))
                .findFirst()
                .orElseThrow();
        assertThat(assign.getAnnotation(CpRequiresRole.class).value())
                .containsExactly(CpPref.ROLE_ADMIN);

        Method desiredState = Arrays.stream(CpInstanceApiController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .filter(method -> Arrays.asList(method.getAnnotation(GetMapping.class).value())
                        .contains("/desired-state"))
                .findFirst()
                .orElseThrow();
        assertThat(desiredState.getParameterTypes())
                .containsExactly(CpInstancePrincipal.class);
    }

    private CpTarget assignAfterBarrier(long instanceId,
                                        AssignTargetCommand command,
                                        long actorId,
                                        CountDownLatch ready,
                                        CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return service.assign(instanceId, command, actorId);
    }

    private long createUser(String login, String role) {
        long userId = jdbc.sql("""
                        insert into cp_users(name, login, email, password_hash)
                        values (:login, :login, :email, 'hash')
                        returning id
                        """)
                .param("login", login)
                .param("email", login + "@example.invalid")
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into cp_user_roles(user_id, role_id)
                        select :userId, id from cp_roles where code = :role
                        """)
                .param("userId", userId)
                .param("role", role)
                .update();
        return userId;
    }

    private Cookie createSession(long userId, String rawSession) {
        jdbc.sql("""
                        insert into cp_sessions(user_id, token_hash, ip, user_agent)
                        values (:userId, :tokenHash, '127.0.0.1', 'target-test')
                        """)
                .param("userId", userId)
                .param("tokenHash", CpPasswordHasher.sha256(rawSession))
                .update();
        return new Cookie(CpPref.SESSION_COOKIE_NAME, rawSession);
    }

    private long createInstance(String clientCode, DeploymentMode mode) {
        long clientId = jdbc.sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values (:code, :name, 'S')
                        returning id
                        """)
                .param("code", clientCode)
                .param("name", clientCode)
                .query(Long.class)
                .single();
        return jdbc.sql("""
                        insert into cp_instances(client_id, environment, url, deployment_mode)
                        values (:clientId, 'production', :url, :mode)
                        returning id
                        """)
                .param("clientId", clientId)
                .param("url", "https://" + clientCode + ".invalid")
                .param("mode", mode.name())
                .query(Long.class)
                .single();
    }

    private String createCredential(long instanceId, String rawCredential) {
        jdbc.sql("""
                        insert into cp_instance_credentials(instance_id, credential_hash)
                        values (:instanceId, :credentialHash)
                        """)
                .param("instanceId", instanceId)
                .param("credentialHash", CpPasswordHasher.sha256(rawCredential))
                .update();
        return rawCredential;
    }

    private UUID createReadyRelease(String version, Set<DeploymentMode> modes) {
        return releaseService.registerVerified(new VerifiedReleaseCommand(
                version,
                "0123456789abcdef0123456789abcdef01234567",
                manifestDigest(version),
                URI.create("https://artifacts.invalid/releases/" + version + "/manifest.json"),
                SHA_B,
                "1",
                "1.0.0",
                modes,
                List.of(
                        component("instance"),
                        component("web"),
                        component("postgres"),
                        component("typesense"),
                        component("proxy"))),
                "build:target-test");
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

    private static String manifestDigest(String version) {
        return "sha256:" + String.valueOf(version.charAt(version.length() - 1)).repeat(64);
    }

    private static AssignTargetCommand command(UUID releaseId, String configVersion) {
        return new AssignTargetCommand(
                releaseId,
                configVersion,
                RolloutRing.R1,
                maintenanceWindow());
    }

    private static MaintenanceWindow maintenanceWindow() {
        return new MaintenanceWindow(2, 3, LocalTime.of(2, 30), 60, "Asia/Tashkent");
    }

    private static CpInstancePrincipal principal(long instanceId) {
        return new CpInstancePrincipal(instanceId, 1, "ignored-by-target-api", 1);
    }

    private static String targetJson(UUID releaseId) {
        return """
                {
                  "releaseId":"%s",
                  "configVersion":"config-api",
                  "ring":"R1",
                  "maintenanceWindow":{
                    "weekOfMonth":2,
                    "dayOfWeek":3,
                    "start":"02:30:00",
                    "durationMinutes":60,
                    "timezone":"Asia/Tashkent"
                  }
                }
                """.formatted(releaseId);
    }

    private static void assertError(ThrowingCall call, String errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(CpApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(errorCode));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
