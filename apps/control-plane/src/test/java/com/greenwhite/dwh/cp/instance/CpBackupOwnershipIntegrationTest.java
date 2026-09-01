package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import com.greenwhite.dwh.cp.pref.CpPref;
import jakarta.servlet.http.Cookie;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class CpBackupOwnershipIntegrationTest {

    private static final String ATTACKER_CREDENTIAL = "attacker-instance-credential";

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

    private long attackerInstanceId;
    private long victimInstanceId;

    @BeforeEach
    void resetData() {
        jdbc.sql("truncate table cp_clients, cp_users restart identity cascade").update();
        attackerInstanceId = createInstance("attacker", ATTACKER_CREDENTIAL);
        victimInstanceId = createInstance("victim", "victim-instance-credential");
    }

    @Test
    void rejectsBodySuppliedClientAndBindsAcceptedReportToAuthenticatedInstance() throws Exception {
        String backupId = "550e8400-e29b-41d4-a716-446655440000";
        String checksum = "a".repeat(64);
        String completedAt = Instant.now().minusSeconds(5).toString();

        mvc.perform(post("/api/v1/instances/backup-reports")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, ATTACKER_CREDENTIAL)
                        .contentType("application/json")
                        .content("""
                                {
                                  "backupId":"%s",
                                  "clientCode":"victim",
                                  "status":"UPLOADED",
                                  "checksumSha256":"%s",
                                  "durationSec":42,
                                  "completedAt":"%s"
                                }
                                """.formatted(backupId, checksum, completedAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("request_malformed"));

        assertThat(reportCount(victimInstanceId)).isZero();
        assertThat(reportCount(attackerInstanceId)).isZero();

        mvc.perform(post("/api/v1/instances/backup-reports")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, ATTACKER_CREDENTIAL)
                        .contentType("application/json")
                        .content("""
                                {
                                  "backupId":"%s",
                                  "status":"UPLOADED",
                                  "checksumSha256":"%s",
                                  "durationSec":42,
                                  "completedAt":"%s"
                                }
                                """.formatted(backupId, checksum, completedAt)))
                .andExpect(status().isAccepted());

        assertThat(reportCount(victimInstanceId)).isZero();
        assertThat(reportCount(attackerInstanceId)).isOne();
    }

    @ParameterizedTest(name = "rejects invalid backup report: {0}")
    @MethodSource("invalidReports")
    void rejectsInvalidReports(String caseName, String body) throws Exception {
        mvc.perform(post("/api/v1/instances/backup-reports")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, ATTACKER_CREDENTIAL)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        assertThat(reportCount(attackerInstanceId)).isZero();
        assertThat(reportCount(victimInstanceId)).isZero();
    }

    @Test
    void identicalRetryIsIdempotentButConflictingContentIsRejected() throws Exception {
        String backupId = "550e8400-e29b-41d4-a716-446655440010";
        String completedAt = Instant.now().minusSeconds(5).toString();
        String body = uploadedReport(backupId, 42, completedAt);

        submit(body).andExpect(status().isAccepted());
        submit(body).andExpect(status().isAccepted());
        assertThat(reportCount(attackerInstanceId)).isOne();

        submit(uploadedReport(backupId, 43, completedAt))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("backup_report_conflict"));
        assertThat(reportCount(attackerInstanceId)).isOne();
    }

    @Test
    void revokedCredentialCannotSubmitAReport() throws Exception {
        jdbc.sql("""
                        update cp_instance_credentials
                        set revoked_at = now()
                        where instance_id = :instanceId
                        """)
                .param("instanceId", attackerInstanceId)
                .update();

        submit(uploadedReport(
                "550e8400-e29b-41d4-a716-446655440020",
                42,
                Instant.now().minusSeconds(5).toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("instance_credential_invalid"));
        assertThat(reportCount(attackerInstanceId)).isZero();
    }

    @Test
    void failedReportAcceptsNoChecksumAndDefaultsMissingDurationToZero() throws Exception {
        String backupId = "550e8400-e29b-41d4-a716-446655440025";
        submit("""
                {
                  "backupId":"%s",
                  "status":"FAILED",
                  "completedAt":"%s",
                  "reasonCode":"storage_upload_failed"
                }
                """.formatted(backupId, Instant.now().minusSeconds(5)))
                .andExpect(status().isAccepted());

        var stored = jdbc.sql("""
                        select artifact_status, checksum_sha256, duration_sec, reason_code
                        from cp_instance_backup_reports
                        where backup_id = cast(:backupId as uuid)
                        """)
                .param("backupId", backupId)
                .query((rs, rowNum) -> new StoredFailure(
                        rs.getString("artifact_status"),
                        rs.getString("checksum_sha256"),
                        rs.getInt("duration_sec"),
                        rs.getString("reason_code")))
                .single();
        assertThat(stored).isEqualTo(new StoredFailure(
                "FAILED", null, 0, "storage_upload_failed"));
    }

    @Test
    void operatorProjectionDerivesClientAndOmitsCredentialsAndObjectLocations() throws Exception {
        String backupId = "550e8400-e29b-41d4-a716-446655440030";
        submit(uploadedReport(backupId, 42, Instant.now().minusSeconds(5).toString()))
                .andExpect(status().isAccepted());

        mvc.perform(get("/api/v1/backup-reports")
                        .cookie(createOperatorSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].backupId").value(backupId))
                .andExpect(jsonPath("$[0].instanceId").value(attackerInstanceId))
                .andExpect(jsonPath("$[0].clientCode").value("attacker"))
                .andExpect(jsonPath("$[0].artifactStatus").value("UPLOADED"))
                .andExpect(jsonPath("$[0].checksumSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$[0].durationSec").value(42))
                .andExpect(jsonPath("$[0].completedAt").isString())
                .andExpect(jsonPath("$[0].receivedAt").isString())
                .andExpect(jsonPath("$[0].verifiedAt").doesNotExist())
                .andExpect(jsonPath("$[0].credential").doesNotExist())
                .andExpect(jsonPath("$[0].objectUrl").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/api/v1/instances/backup-reports")
                .header(CpInstanceAuthFilter.TOKEN_HEADER, ATTACKER_CREDENTIAL)
                .contentType("application/json")
                .content(body));
    }

    private static String uploadedReport(String backupId, int durationSec, String completedAt) {
        return """
                {
                  "backupId":"%s",
                  "status":"UPLOADED",
                  "checksumSha256":"%s",
                  "durationSec":%d,
                  "completedAt":"%s"
                }
                """.formatted(backupId, "a".repeat(64), durationSec, completedAt);
    }

    private static Stream<Arguments> invalidReports() {
        String backupId = "550e8400-e29b-41d4-a716-446655440040";
        String past = Instant.now().minusSeconds(5).toString();
        String future = Instant.now().plusSeconds(3600).toString();
        String checksum = "a".repeat(64);
        return Stream.of(
                Arguments.of("negative duration", """
                        {"backupId":"%s","status":"FAILED","durationSec":-1,"completedAt":"%s"}
                        """.formatted(backupId, past)),
                Arguments.of("duration above one day", """
                        {"backupId":"%s","status":"FAILED","durationSec":86401,"completedAt":"%s"}
                        """.formatted(backupId, past)),
                Arguments.of("future completion", """
                        {"backupId":"%s","status":"FAILED","durationSec":1,"completedAt":"%s"}
                        """.formatted(backupId, future)),
                Arguments.of("malformed checksum", """
                        {"backupId":"%s","status":"UPLOADED","checksumSha256":"bad","completedAt":"%s"}
                        """.formatted(backupId, past)),
                Arguments.of("uploaded without checksum", """
                        {"backupId":"%s","status":"UPLOADED","completedAt":"%s"}
                        """.formatted(backupId, past)),
                Arguments.of("failed with checksum", """
                        {"backupId":"%s","status":"FAILED","checksumSha256":"%s","completedAt":"%s"}
                        """.formatted(backupId, checksum, past)),
                Arguments.of("instance supplied verified status", """
                        {"backupId":"%s","status":"VERIFIED","completedAt":"%s"}
                        """.formatted(backupId, past)),
                Arguments.of("unknown property", """
                        {"backupId":"%s","status":"FAILED","completedAt":"%s","clientCode":"victim"}
                        """.formatted(backupId, past)),
                Arguments.of("missing status", """
                        {"backupId":"%s","completedAt":"%s"}
                        """.formatted(backupId, past)));
    }

    private Cookie createOperatorSession() {
        long userId = jdbc.sql("""
                        insert into cp_users(name, login, email, password_hash)
                        values ('Engineer', 'engineer', 'engineer@example.invalid', 'hash')
                        returning id
                        """)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into cp_user_roles(user_id, role_id)
                        select :userId, id from cp_roles where code = 'cp-engineer'
                        """)
                .param("userId", userId)
                .update();
        String rawSession = "engineer-session";
        jdbc.sql("""
                        insert into cp_sessions(user_id, token_hash, ip, user_agent)
                        values (:userId, :tokenHash, '127.0.0.1', 'test')
                        """)
                .param("userId", userId)
                .param("tokenHash", CpPasswordHasher.sha256(rawSession))
                .update();
        return new Cookie(CpPref.SESSION_COOKIE_NAME, rawSession);
    }

    private long createInstance(String clientCode, String rawCredential) {
        long clientId = jdbc.sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values (:code, :name, 'S')
                        returning id
                        """)
                .param("code", clientCode)
                .param("name", clientCode)
                .query(Long.class)
                .single();
        long instanceId = jdbc.sql("""
                        insert into cp_instances(client_id, environment, url)
                        values (:clientId, 'production', :url)
                        returning id
                        """)
                .param("clientId", clientId)
                .param("url", "https://" + clientCode + ".invalid")
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into cp_instance_credentials(instance_id, credential_hash)
                        values (:instanceId, :credentialHash)
                        """)
                .param("instanceId", instanceId)
                .param("credentialHash", CpPasswordHasher.sha256(rawCredential))
                .update();
        return instanceId;
    }

    private long reportCount(long instanceId) {
        return jdbc.sql("""
                        select count(*)
                        from cp_instance_backup_reports
                        where instance_id = :instanceId
                        """)
                .param("instanceId", instanceId)
                .query(Long.class)
                .single();
    }

    private record StoredFailure(
            String status,
            String checksumSha256,
            int durationSec,
            String reasonCode) {
    }
}
