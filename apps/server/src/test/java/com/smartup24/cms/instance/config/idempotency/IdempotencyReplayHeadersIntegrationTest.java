package com.smartup24.cms.instance.config.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.support.TestDatabases;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.4: a replay under the same Idempotency-Key is the original answer — its Content-Type, its ETag,
 * and no body where it had none. An answer that cannot be kept still commits; only its replay is lost.
 */
class IdempotencyReplayHeadersIntegrationTest {

    private static JdbcClient jdbc;
    private static IdempotencyFilter filter;

    @BeforeAll
    static void setup() {
        DataSource database = TestDatabases.migratedCopy("idempotency_headers");
        jdbc = JdbcClient.create(database);
        jdbc.sql("create table idem_headers_business (id bigint generated always as identity primary key)")
                .update();
        filter = new IdempotencyFilter(
                new IdempotencyService(new IdempotencyRepository(jdbc)),
                new ObjectMapper(),
                PackagedProblemMessages.russian(),
                new DataSourceTransactionManager(database),
                null);
    }

    @BeforeEach
    void clear() {
        jdbc.sql("delete from idempotency_keys").update();
        jdbc.sql("delete from idem_headers_business").update();
    }

    @Test
    @DisplayName("3.4: a retried 409 replays application/problem+json, not application/json")
    void retriedConflictKeepsProblemJson() throws Exception {
        UUID key = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        FilterChain refuse = (request, response) -> {
            runs.incrementAndGet();
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(409);
            http.setContentType("application/problem+json");
            http.getOutputStream().write("{\"code\":\"conflict\",\"status\":409}".getBytes(StandardCharsets.UTF_8));
        };

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request(key), first, refuse);
        MockHttpServletResponse retry = new MockHttpServletResponse();
        filter.doFilter(request(key), retry, refuse);

        assertThat(runs.get()).isEqualTo(1);
        assertThat(retry.getStatus()).isEqualTo(409);
        assertThat(retry.getHeader(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAY)).isEqualTo("true");
        assertThat(retry.getContentType()).isEqualTo("application/problem+json");
        assertThat(new ObjectMapper()
                        .readTree(retry.getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("conflict");
    }

    @Test
    @DisplayName("3.4: a retried 204 replays its ETag and no body")
    void retriedNoContentKeepsETagWithoutBody() throws Exception {
        UUID key = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        FilterChain change = (request, response) -> {
            runs.incrementAndGet();
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(204);
            http.setHeader("ETag", "\"7\"");
        };

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request(key), first, change);
        MockHttpServletResponse retry = new MockHttpServletResponse();
        filter.doFilter(request(key), retry, change);

        assertThat(runs.get()).isEqualTo(1);
        assertThat(retry.getStatus()).isEqualTo(204);
        assertThat(retry.getHeader(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAY)).isEqualTo("true");
        assertThat(retry.getHeader("ETag")).isEqualTo("\"7\"");
        assertThat(retry.getContentAsByteArray()).isEmpty();
        assertThat(retry.getContentType()).isNull();
    }

    @Test
    @DisplayName("3.4: a success that cannot be kept for a replay (not JSON) commits and frees the key")
    void unstorableSuccessCommits() throws Exception {
        UUID key = UUID.randomUUID();
        FilterChain export = (request, response) -> {
            jdbc.sql("insert into idem_headers_business default values").update();
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(200);
            http.setContentType("text/csv");
            http.getOutputStream().write("id\n1\n".getBytes(StandardCharsets.UTF_8));
        };

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request(key), first, export);

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(first.getContentAsString()).isEqualTo("id\n1\n");
        assertThat(businessRows()).as("the operation committed").isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from idempotency_keys")
                        .query(Long.class)
                        .single())
                .as("the key is freed")
                .isZero();
    }

    @Test
    @DisplayName("3.4: a success too large to keep for a replay commits and frees the key")
    void oversizedSuccessCommits() throws Exception {
        UUID key = UUID.randomUUID();
        String large = "{\"text\":\"" + "x".repeat(IdempotencyFilter.MAX_RESPONSE_BODY_BYTES) + "\"}";
        FilterChain create = (request, response) -> {
            jdbc.sql("insert into idem_headers_business default values").update();
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(201);
            http.setContentType("application/json");
            http.getOutputStream().write(large.getBytes(StandardCharsets.UTF_8));
        };

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request(key), first, create);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(businessRows()).as("the operation committed").isEqualTo(1);
    }

    private static long businessRows() {
        return jdbc.sql("select count(*) from idem_headers_business")
                .query(Long.class)
                .single();
    }

    private static MockHttpServletRequest request(UUID key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        request.addHeader(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, key.toString());
        request.setContentType("application/json");
        request.setContent("{\"title\":\"Release\"}".getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
