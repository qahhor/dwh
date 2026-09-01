package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
        "dwh.cp.admin-password=",
        "dwh.cp.instance-api.max-body-bytes=16384",
        "dwh.cp.instance-api.heartbeat-capacity=2",
        "dwh.cp.instance-api.heartbeat-refill=1m"
})
@AutoConfigureMockMvc
@Testcontainers
class CpInstanceRequestGuardFilterTest {

    private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger();

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

    @Autowired
    private CpInstanceRequestGuardFilter guard;

    private String credential;

    @BeforeEach
    void registerInstance() {
        int sequence = CLIENT_SEQUENCE.incrementAndGet();
        credential = "guard-credential-" + sequence;
        long clientId = jdbc.sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values (:code, :name, 'M')
                        returning id
                        """)
                .param("code", "guard-" + sequence)
                .param("name", "Guard " + sequence)
                .query(Long.class)
                .single();
        long instanceId = jdbc.sql("""
                        insert into cp_instances(client_id, environment, url, license_status)
                        values (:clientId, 'production', :url, 'ACTIVE')
                        returning id
                        """)
                .param("clientId", clientId)
                .param("url", "https://guard-" + sequence + ".invalid")
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into cp_instance_credentials(instance_id, credential_hash)
                        values (:instanceId, :credentialHash)
                        """)
                .param("instanceId", instanceId)
                .param("credentialHash", CpPasswordHasher.sha256(credential))
                .update();
    }

    @Test
    void acceptsBodyAtExactSixteenKibBoundary() throws Exception {
        mvc.perform(post("/api/v1/instances/heartbeat")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, credential)
                        .contentType("application/json")
                        .content(bodyOfBytes(16_384)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsBodyOneByteAboveBoundaryWithProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/instances/heartbeat")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, credential)
                        .contentType("application/json")
                        .content(bodyOfBytes(16_385)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errorCode").value("instance_payload_too_large"));
    }

    @Test
    void limitsEveryInstancePostEndpointBeforeBodyParsing() throws Exception {
        mvc.perform(post("/api/v1/instances/backup-reports")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, credential)
                        .contentType("application/json")
                        .content(new byte[16_385]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("instance_payload_too_large"));
    }

    @Test
    void rejectsThirdHeartbeatPerMinuteForAuthenticatedInstance() throws Exception {
        byte[] body = bodyOfBytes(128);
        for (int request = 0; request < 2; request++) {
            mvc.perform(post("/api/v1/instances/heartbeat")
                            .header(CpInstanceAuthFilter.TOKEN_HEADER, credential)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/v1/instances/heartbeat")
                        .header(CpInstanceAuthFilter.TOKEN_HEADER, credential)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errorCode").value("instance_rate_limited"));
    }

    @Test
    void chunkedRequestCannotBypassLimitWhenContentLengthIsUnknown() throws Exception {
        byte[] body = bodyOfBytes(16_385);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/instances/heartbeat") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, (wrappedRequest, wrappedResponse) ->
                wrappedRequest.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"instance_payload_too_large\"");
    }

    private static byte[] bodyOfBytes(int targetLength) {
        byte[] json = "{\"appVersion\":\"1.2.3\",\"schemaVersion\":\"006\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(targetLength).isGreaterThanOrEqualTo(json.length);
        byte[] body = new byte[targetLength];
        System.arraycopy(json, 0, body, 0, json.length);
        java.util.Arrays.fill(body, json.length, body.length, (byte) ' ');
        assertThat(body).hasSize(targetLength);
        return body;
    }
}
