package com.smartup24.cms.instance.config.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.support.TestDatabases;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.12: an operation with an Idempotency-Key happens exactly once. Its writes and the record of its
 * answer commit together, so a process that dies after the commit leaves the answer for the retry.
 */
class IdempotencyExactlyOnceIntegrationTest {

    private static JdbcClient jdbc;
    private static DataSourceTransactionManager transactions;
    private static IdempotencyFilter filter;

    @BeforeAll
    static void setup() {
        DataSource database = TestDatabases.migratedCopy("idempotency_once");
        jdbc = JdbcClient.create(database);
        jdbc.sql("create table idem_business (id bigint generated always as identity primary key, note text)")
                .update();
        jdbc.sql("""
                create table idem_deferred (
                    note text,
                    constraint idem_deferred_uk unique (note) deferrable initially deferred)
                """).update();
        transactions = new DataSourceTransactionManager(database);
        filter = new IdempotencyFilter(
                new IdempotencyService(new IdempotencyRepository(jdbc)),
                new ObjectMapper(),
                PackagedProblemMessages.russian(),
                transactions,
                null);
    }

    @BeforeEach
    void clear() {
        jdbc.sql("delete from idempotency_keys").update();
        jdbc.sql("delete from idem_business").update();
        jdbc.sql("delete from idem_deferred").update();
    }

    @Test
    @DisplayName("3.12: the process dies after the commit, before the answer leaves: the retry gets the stored answer")
    void crashAfterCommitReplaysTheStoredAnswer() throws Exception {
        UUID key = UUID.randomUUID();
        FilterChain create = (request, response) -> {
            jdbc.sql("insert into idem_business (note) values ('created')").update();
            respond((HttpServletResponse) response, 201, "{\"id\":42}");
        };

        assertThatThrownBy(() -> filter.doFilter(request(key), new DyingResponse(), create))
                .isInstanceOf(IOException.class);
        assertThat(rows()).as("the operation committed with its answer").isEqualTo(1);

        MockHttpServletResponse retry = new MockHttpServletResponse();
        filter.doFilter(request(key), retry, create);

        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getHeader(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAY)).isEqualTo("true");
        // Stored as jsonb: the same JSON, whitespace aside.
        assertThat(new ObjectMapper().readTree(retry.getContentAsString()))
                .isEqualTo(new ObjectMapper().readTree("{\"id\":42}"));
        assertThat(rows()).as("no second execution").isEqualTo(1);
    }

    @Test
    @DisplayName("3.12: a 5xx rolls the request back and frees the key, so the retry runs again")
    void serverErrorRollsBackAndFreesTheKey() throws Exception {
        UUID key = UUID.randomUUID();
        MockHttpServletResponse failed = new MockHttpServletResponse();
        filter.doFilter(request(key), failed, (request, response) -> {
            jdbc.sql("insert into idem_business (note) values ('half done')").update();
            respond((HttpServletResponse) response, 500, "{\"code\":\"internal_error\"}");
        });
        assertThat(failed.getStatus()).isEqualTo(500);
        assertThat(rows()).as("nothing of the failed request stays").isZero();

        MockHttpServletResponse retry = new MockHttpServletResponse();
        filter.doFilter(request(key), retry, (request, response) -> {
            jdbc.sql("insert into idem_business (note) values ('done')").update();
            respond((HttpServletResponse) response, 201, "{\"id\":7}");
        });
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getHeader(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAY)).isNull();
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("3.12: a refusal whose business transaction rolled back is recorded alone and replayed")
    void refusalAfterRollbackIsRecordedAndReplayed() throws Exception {
        UUID key = UUID.randomUUID();
        FilterChain refuse = (request, response) -> {
            // What a @Transactional service does when it throws: its participation marks the request rollback-only.
            var inner = transactions.getTransaction(new DefaultTransactionDefinition());
            jdbc.sql("insert into idem_business (note) values ('refused')").update();
            inner.setRollbackOnly();
            transactions.commit(inner);
            respond((HttpServletResponse) response, 409, "{\"code\":\"conflict\"}");
        };

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request(key), first, refuse);
        assertThat(first.getStatus()).isEqualTo(409);
        assertThat(rows()).isZero();

        MockHttpServletResponse retry = new MockHttpServletResponse();
        filter.doFilter(request(key), retry, refuse);
        assertThat(retry.getStatus()).isEqualTo(409);
        assertThat(retry.getHeader(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAY)).isEqualTo("true");
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName(
            "3.12: when the commit itself fails the client gets a 500, not the buffered success, and the key is free")
    void failedCommitAnswersServerError() throws Exception {
        UUID key = UUID.randomUUID();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(key), response, (request, servletResponse) -> {
            // A deferred unique constraint fails only at commit, after the handler has answered.
            jdbc.sql("insert into idem_deferred (note) values ('same'), ('same')")
                    .update();
            respond((HttpServletResponse) servletResponse, 201, "{\"id\":1}");
        });

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("internal_error").doesNotContain("\"id\":1");
        assertThat(jdbc.sql("select count(*) from idem_deferred")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from idempotency_keys where key = :key and state = 'COMPLETED'")
                        .param("key", key)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private static long rows() {
        return jdbc.sql("select count(*) from idem_business").query(Long.class).single();
    }

    private static MockHttpServletRequest request(UUID key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks/items");
        request.addHeader(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, key.toString());
        request.setContentType("application/json");
        request.setContent("{\"title\":\"Release\"}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static void respond(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A response whose connection is gone when the answer is written: the process died after the commit. */
    private static final class DyingResponse extends MockHttpServletResponse {
        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("the process died before the answer left");
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
            };
        }
    }
}
