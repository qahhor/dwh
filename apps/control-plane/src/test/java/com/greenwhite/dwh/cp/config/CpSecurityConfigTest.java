package com.greenwhite.dwh.cp.config;

import com.greenwhite.dwh.cp.error.CpRequestTraceFilter;
import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.security.CpPasswordHasher;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class CpSecurityConfigTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT =
            "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

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

    @BeforeEach
    void resetData() {
        jdbc.sql("truncate table cp_clients, cp_users restart identity cascade").update();
    }

    @Test
    void protectedInstancePathRequiresCredentialAndReturnsCorrelatedProblem() throws Exception {
        mvc.perform(post("/api/v1/instances/heartbeat")
                        .header(CpRequestTraceFilter.TRACEPARENT_HEADER, TRACEPARENT)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CpRequestTraceFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.errorCode").value("instance_credential_invalid"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void operatorCookieCannotImpersonateAnInstance() throws Exception {
        Cookie operator = createOperatorSession();

        mvc.perform(post("/api/v1/instances/heartbeat")
                        .with(csrf())
                        .cookie(operator)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("instance_credential_invalid"));
    }

    @Test
    void validInstanceCredentialCannotCallOperatorApi() throws Exception {
        String rawCredential = createInstanceCredential();

        mvc.perform(get("/api/v1/clients")
                        .header("X-Instance-Token", rawCredential)
                        .header(CpRequestTraceFilter.TRACEPARENT_HEADER, TRACEPARENT))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CpRequestTraceFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errorCode").value("unauthorized"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void enrollmentIsPublicButCanOnlyBeExchangedOnce() throws Exception {
        String rawEnrollment = createEnrollment();

        mvc.perform(post("/api/v1/instances/enroll")
                        .contentType("application/json")
                        .content("""
                                {"enrollmentToken":"%s"}
                                """.formatted(rawEnrollment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").isNumber())
                .andExpect(jsonPath("$.credential").isString())
                .andExpect(jsonPath("$.enrollmentToken").doesNotExist());

        mvc.perform(post("/api/v1/instances/enroll")
                        .header(CpRequestTraceFilter.TRACEPARENT_HEADER, TRACEPARENT)
                        .contentType("application/json")
                        .content("""
                                {"enrollmentToken":"%s"}
                                """.formatted(rawEnrollment)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CpRequestTraceFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(jsonPath("$.errorCode").value("instance_enrollment_invalid"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void operatorRegistrationReturnsOneTimeEnrollmentAndPersistsApprovedPlacement() throws Exception {
        Cookie operator = createOperatorSession();
        jdbc.sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values ('alpha', 'Alpha', 'S')
                        """)
                .update();

        var result = mvc.perform(post("/api/v1/instances")
                        .with(csrf())
                        .cookie(operator)
                        .contentType("application/json")
                        .content("""
                                {
                                  "clientCode":"alpha",
                                  "environment":"production",
                                  "url":"https://alpha.invalid",
                                  "deploymentMode":"MANAGED_CLOUD",
                                  "jurisdiction":"EU",
                                  "cloudProvider":"HETZNER",
                                  "storageProvider":"CLOUDFLARE_R2",
                                  "edgeProvider":"CLOUDFLARE",
                                  "supportTier":"MANAGED_995"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instanceId").isNumber())
                .andExpect(jsonPath("$.enrollmentToken").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.heartbeatToken").doesNotExist())
                .andReturn();

        long instanceId = new tools.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .get("instanceId")
                .asLong();
        var placement = jdbc.sql("""
                        select deployment_mode, jurisdiction, cloud_provider,
                               storage_provider, edge_provider, support_tier,
                               heartbeat_token_hash, lifecycle_status
                        from cp_instances
                        where id = :instanceId
                        """)
                .param("instanceId", instanceId)
                .query((rs, rowNum) -> new Placement(
                        rs.getString("deployment_mode"),
                        rs.getString("jurisdiction"),
                        rs.getString("cloud_provider"),
                        rs.getString("storage_provider"),
                        rs.getString("edge_provider"),
                        rs.getString("support_tier"),
                        rs.getString("heartbeat_token_hash"),
                        rs.getString("lifecycle_status")))
                .single();
        assertThat(placement).isEqualTo(new Placement(
                "MANAGED_CLOUD", "EU", "HETZNER", "CLOUDFLARE_R2",
                "CLOUDFLARE", "MANAGED_995", null, "ENROLLING"));
    }

    @Test
    void malformedAndUnknownJsonUseStableProblemContract() throws Exception {
        mvc.perform(post("/api/v1/instances/enroll")
                        .header(CpRequestTraceFilter.TRACEPARENT_HEADER, TRACEPARENT)
                        .contentType("application/json")
                        .content("""
                                {"enrollmentToken":"value","unexpected":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(CpRequestTraceFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errorCode").value("request_malformed"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    private Cookie createOperatorSession() {
        long userId = jdbc.sql("""
                        insert into cp_users(name, login, email, password_hash)
                        values ('Operator', 'operator', 'operator@example.invalid', 'hash')
                        returning id
                        """)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into cp_user_roles(user_id, role_id)
                        select :userId, id from cp_roles where code = 'cp-admin'
                        """)
                .param("userId", userId)
                .update();
        String rawSession = "operator-session";
        jdbc.sql("""
                        insert into cp_sessions(user_id, token_hash, ip, user_agent)
                        values (:userId, :tokenHash, '127.0.0.1', 'test')
                        """)
                .param("userId", userId)
                .param("tokenHash", CpPasswordHasher.sha256(rawSession))
                .update();
        return new Cookie(CpPref.SESSION_COOKIE_NAME, rawSession);
    }

    private String createInstanceCredential() {
        long instanceId = createInstance();
        String rawCredential = "instance-secret";
        jdbc.sql("""
                        insert into cp_instance_credentials(instance_id, credential_hash)
                        values (:instanceId, :credentialHash)
                        """)
                .param("instanceId", instanceId)
                .param("credentialHash", CpPasswordHasher.sha256(rawCredential))
                .update();
        return rawCredential;
    }

    private String createEnrollment() {
        long userId = jdbc.sql("""
                        insert into cp_users(name, login, email, password_hash)
                        values ('Operator', 'operator', 'operator@example.invalid', 'hash')
                        returning id
                        """)
                .query(Long.class)
                .single();
        long instanceId = createInstance();
        String rawEnrollment = "one-time-enrollment";
        jdbc.sql("""
                        insert into cp_instance_enrollment_tokens(
                            instance_id, token_hash, expires_at, created_by)
                        values (:instanceId, :tokenHash, :expiresAt, :userId)
                        """)
                .param("instanceId", instanceId)
                .param("tokenHash", CpPasswordHasher.sha256(rawEnrollment))
                .param("expiresAt", dbTime(Instant.now().plusSeconds(900)))
                .param("userId", userId)
                .update();
        return rawEnrollment;
    }

    private long createInstance() {
        long clientId = jdbc.sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values ('alpha', 'Alpha', 'S')
                        returning id
                        """)
                .query(Long.class)
                .single();
        return jdbc.sql("""
                        insert into cp_instances(client_id, environment, url)
                        values (:clientId, 'production', 'https://alpha.invalid')
                        returning id
                        """)
                .param("clientId", clientId)
                .query(Long.class)
                .single();
    }

    private static OffsetDateTime dbTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Placement(
            String deploymentMode,
            String jurisdiction,
            String cloudProvider,
            String storageProvider,
            String edgeProvider,
            String supportTier,
            String heartbeatTokenHash,
            String lifecycleStatus) {
    }
}
