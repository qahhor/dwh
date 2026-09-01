package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
class CpHeartbeatContractIntegrationTest {

    private static final String RAW_CREDENTIAL = "typed-heartbeat-credential";

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
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private long instanceId;

    @BeforeEach
    void resetData() {
        jdbc.sql("truncate table cp_clients, cp_users restart identity cascade").update();
        long clientId = jdbc.sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values ('alpha', 'Alpha', 'M')
                        returning id
                        """)
                .query(Long.class)
                .single();
        instanceId = jdbc.sql("""
                        insert into cp_instances(
                            client_id, environment, url, license_status)
                        values (:clientId, 'production', 'https://alpha.invalid', 'ACTIVE')
                        returning id
                        """)
                .param("clientId", clientId)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into cp_instance_credentials(instance_id, credential_hash)
                        values (:instanceId, :credentialHash)
                        """)
                .param("instanceId", instanceId)
                .param("credentialHash", CpPasswordHasher.sha256(RAW_CREDENTIAL))
                .update();
    }

    @Test
    void acceptsOnlyTypedTelemetryAndPersistsItForAuthenticatedInstance() throws Exception {
        String completedAt = Instant.now().minusSeconds(60).toString();

        mvc.perform(post("/api/v1/instances/heartbeat")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, RAW_CREDENTIAL)
                        .contentType("application/json")
                        .content(validHeartbeat(completedAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.instanceId").value(instanceId))
                .andExpect(jsonPath("$.licenseStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.resourceProfile").value("M"))
                .andExpect(jsonPath("$.desiredGeneration").value(0))
                .andExpect(jsonPath("$.clientCode").doesNotExist());

        var stored = jdbc.sql("""
                        select heartbeat.instance_id, heartbeat.app_version,
                               heartbeat.schema_version, heartbeat.release_version,
                               heartbeat.config_version,
                               heartbeat.component_health ->> 'app' as app_health,
                               heartbeat.component_health ->> 'objectStorage' as object_storage_health,
                               heartbeat.storage_used_bytes, heartbeat.storage_quota_bytes,
                               heartbeat.last_backup_at, heartbeat.backup_status,
                               heartbeat.tunnel_status, heartbeat.agent_status,
                               heartbeat.deployment_state, heartbeat.active_users,
                               heartbeat.outbox_pending, heartbeat.outbox_dead_letter,
                               heartbeat.metrics = '{}'::jsonb as empty_legacy_metrics
                        from cp_instance_heartbeats heartbeat
                        """)
                .query((rs, rowNum) -> new StoredHeartbeat(
                        rs.getLong("instance_id"),
                        rs.getString("app_version"),
                        rs.getString("schema_version"),
                        rs.getString("release_version"),
                        rs.getString("config_version"),
                        rs.getString("app_health"),
                        rs.getString("object_storage_health"),
                        rs.getLong("storage_used_bytes"),
                        rs.getLong("storage_quota_bytes"),
                        rs.getTimestamp("last_backup_at").toInstant(),
                        rs.getString("backup_status"),
                        rs.getString("tunnel_status"),
                        rs.getString("agent_status"),
                        rs.getString("deployment_state"),
                        rs.getLong("active_users"),
                        rs.getLong("outbox_pending"),
                        rs.getLong("outbox_dead_letter"),
                        rs.getBoolean("empty_legacy_metrics")))
                .single();

        assertThat(stored).isEqualTo(new StoredHeartbeat(
                instanceId,
                "1.2.3",
                "006",
                "2026.09.1",
                "cfg-17",
                "UP",
                "DEGRADED",
                1024,
                4096,
                Instant.parse(completedAt).truncatedTo(ChronoUnit.MICROS),
                "UPLOADED",
                "UP",
                "DEGRADED",
                "VERIFYING",
                17,
                3,
                1,
                true));
    }

    @Test
    void acceptsOmittedOptionalTelemetrySectionsWithoutInventingMeasurements() throws Exception {
        mvc.perform(post("/api/v1/instances/heartbeat")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, RAW_CREDENTIAL)
                        .contentType("application/json")
                        .content("""
                                {"appVersion":"1.2.3","schemaVersion":"006"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value(instanceId));

        var optionalFields = jdbc.sql("""
                        select component_health = '{}'::jsonb as empty_components,
                               storage_used_bytes is null as storage_unknown,
                               backup_status is null as backup_unknown,
                               active_users is null as capacity_unknown
                        from cp_instance_heartbeats
                        """)
                .query((rs, rowNum) -> new OptionalFields(
                        rs.getBoolean("empty_components"),
                        rs.getBoolean("storage_unknown"),
                        rs.getBoolean("backup_unknown"),
                        rs.getBoolean("capacity_unknown")))
                .single();
        assertThat(optionalFields).isEqualTo(new OptionalFields(true, true, true, true));
    }

    @ParameterizedTest(name = "rejects non-contract telemetry: {0}")
    @MethodSource("invalidHeartbeats")
    void rejectsPiiArbitraryMapsNegativeCountersAndUnknownNestedFields(
            String caseName,
            String body) throws Exception {
        mvc.perform(post("/api/v1/instances/heartbeat")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, RAW_CREDENTIAL)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        assertThat(jdbc.sql("select count(*) from cp_instance_heartbeats")
                .query(Long.class)
                .single()).isZero();
    }

    private static Stream<Arguments> invalidHeartbeats() {
        String base = "{\"appVersion\":\"1.2.3\",\"schemaVersion\":\"006\"";
        return Stream.of(
                Arguments.of("users", base + ",\"users\":[\"alice\"]}"),
                Arguments.of("emails", base + ",\"emails\":[\"a@example.invalid\"]}"),
                Arguments.of("file names", base + ",\"fileNames\":[\"payroll.xlsx\"]}"),
                Arguments.of("arbitrary metrics", base + ",\"metrics\":{\"anything\":true}}"),
                Arguments.of("negative active users", base
                        + ",\"capacity\":{\"activeUsers\":-1,\"outboxPending\":0,\"outboxDeadLetter\":0}}"),
                Arguments.of("negative storage", base
                        + ",\"storage\":{\"usedBytes\":-1,\"quotaBytes\":0}}"),
                Arguments.of("unknown nested property", base
                        + ",\"components\":{\"app\":\"UP\",\"hostName\":\"secret-host\"}}"));
    }

    private static String validHeartbeat(String completedAt) {
        return """
                {
                  "appVersion":"1.2.3",
                  "schemaVersion":"006",
                  "releaseVersion":"2026.09.1",
                  "configVersion":"cfg-17",
                  "components":{
                    "app":"UP",
                    "database":"UP",
                    "typesense":"UNKNOWN",
                    "objectStorage":"DEGRADED"
                  },
                  "storage":{"usedBytes":1024,"quotaBytes":4096},
                  "backup":{"lastCompletedAt":"%s","status":"UPLOADED"},
                  "agents":{"tunnel":"UP","telemetry":"DEGRADED"},
                  "deploymentState":"VERIFYING",
                  "capacity":{"activeUsers":17,"outboxPending":3,"outboxDeadLetter":1}
                }
                """.formatted(completedAt);
    }

    private record StoredHeartbeat(
            long instanceId,
            String appVersion,
            String schemaVersion,
            String releaseVersion,
            String configVersion,
            String appHealth,
            String objectStorageHealth,
            long storageUsedBytes,
            long storageQuotaBytes,
            Instant lastBackupAt,
            String backupStatus,
            String tunnelStatus,
            String agentStatus,
            String deploymentState,
            long activeUsers,
            long outboxPending,
            long outboxDeadLetter,
            boolean emptyLegacyMetrics) {
    }

    private record OptionalFields(
            boolean emptyComponents,
            boolean storageUnknown,
            boolean backupUnknown,
            boolean capacityUnknown) {
    }
}
