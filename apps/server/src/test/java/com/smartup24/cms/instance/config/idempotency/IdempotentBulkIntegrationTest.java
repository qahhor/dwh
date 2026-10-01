package com.smartup24.cms.instance.config.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.bulk.BulkItemScope;
import com.smartup24.cms.instance.common.bulk.BulkRunner;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.support.TestDatabases;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, items 3.12 and 3.6: a bulk action sent with an Idempotency-Key runs in the one transaction of the
 * request. An item whose {@code @Transactional} service refuses rolls back alone (its savepoint, {@link BulkItemScope});
 * the request still answers 200 with the partial result, the other items commit, and the retry replays that answer
 * without running any item again.
 */
class IdempotentBulkIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JdbcClient jdbc;
    private static IdempotencyFilter filter;
    private static BulkItemScope scope;
    private static ItemService service;
    private static ClosingService target;

    /** A single operation as a module service declares it. */
    interface ItemService {
        void close(long id);
    }

    /** Writes, then refuses the item {@code 2} the way a service does: an ApiException out of a transaction. */
    static class ClosingService implements ItemService {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        @Transactional
        public void close(long id) {
            calls.incrementAndGet();
            jdbc.sql("insert into idem_bulk (item) values (:id)")
                    .param("id", id)
                    .update();
            if (id == 2) {
                throw ApiException.conflict(ErrorCode.REVISION_CONFLICT, "error.common.revision_conflict");
            }
        }
    }

    @BeforeAll
    static void setup() {
        DataSource database = TestDatabases.migratedCopy("idempotency_bulk");
        jdbc = JdbcClient.create(database);
        jdbc.sql("create table idem_bulk (item bigint not null)").update();
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(database);
        filter = new IdempotencyFilter(
                new IdempotencyService(new IdempotencyRepository(jdbc)),
                JSON,
                PackagedProblemMessages.russian(),
                transactions,
                null);
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("transactionManager", transactions));
        scope = new BulkItemScope(beans.getBeanProvider(PlatformTransactionManager.class));
        target = new ClosingService();
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addInterface(ItemService.class);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (ItemService) proxy.getProxy();
    }

    @Test
    @DisplayName("3.12: one failing item of an idempotent bulk rolls back alone; the answer is 200 and replays as is")
    void failingItemRollsBackAloneAndTheAnswerReplays() throws Exception {
        UUID key = UUID.randomUUID();
        FilterChain bulk = (request, response) -> {
            BulkRunner.BulkResult result = BulkRunner.run("close", List.of(1L, 2L, 3L), service::close, scope);
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(200);
            http.setContentType("application/json");
            http.getOutputStream().write(JSON.writeValueAsBytes(result));
        };

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request(key), first, bulk);

        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
        JsonNode answer = JSON.readTree(first.getContentAsString());
        assertThat(answer.get("succeeded").asInt()).isEqualTo(2);
        assertThat(answer.get("failed").asInt()).isEqualTo(1);
        assertThat(answer.get("results").get(1).get("ok").asBoolean()).isFalse();
        assertThat(answer.get("results").get(1).get("messageKey").asString())
                .isEqualTo("error.common.revision_conflict");
        assertThat(items())
                .as("the other items committed, the refused one rolled back")
                .containsExactly(1L, 3L);
        int calls = target.calls.get();

        MockHttpServletResponse retry = new MockHttpServletResponse();
        filter.doFilter(request(key), retry, bulk);

        assertThat(retry.getStatus()).isEqualTo(200);
        assertThat(retry.getHeader(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAY)).isEqualTo("true");
        assertThat(JSON.readTree(retry.getContentAsString())).isEqualTo(answer);
        assertThat(target.calls.get()).as("no item runs again").isEqualTo(calls);
        assertThat(items()).containsExactly(1L, 3L);
    }

    private static List<Long> items() {
        return jdbc.sql("select item from idem_bulk order by item")
                .query(Long.class)
                .list();
    }

    private static MockHttpServletRequest request(UUID key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks/bulk");
        request.addHeader(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, key.toString());
        request.setContentType("application/json");
        request.setContent("{\"action\":\"close\",\"ids\":[1,2,3]}".getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
