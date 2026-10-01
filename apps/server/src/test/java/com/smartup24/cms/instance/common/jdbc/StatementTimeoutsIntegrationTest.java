package com.smartup24.cms.instance.common.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.search.repository.SearchJobRepository;
import com.smartup24.cms.instance.support.TestDatabases;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A request with an Idempotency-Key runs its handler inside the filter's transaction, where an inner
 * {@code @Transactional(timeout = 2)} is ignored; the statement limit holds there all the same.
 */
class StatementTimeoutsIntegrationTest {

    static DataSource ds;
    static JdbcClient jdbc;
    static TransactionTemplate outer;

    @BeforeAll
    static void setup() {
        ds = TestDatabases.migratedCopy("smc_statement_timeouts_test");
        jdbc = JdbcClient.create(ds);
        outer = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @Test
    @DisplayName("Inside an outer transaction a job state change waits for a lock no longer than its limit")
    void limitHoldsInsideAnOuterTransaction() throws Exception {
        var jobs = new SearchJobRepository(jdbc, new ObjectMapper());
        try (Connection holder = ds.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.createStatement()) {
                lock.execute("select id from search_index_state where id = 1 for update");
            }
            long started = System.nanoTime();
            assertThatThrownBy(() -> outer.executeWithoutResult(tx -> {
                        // A safety net, so a broken limit fails the test instead of hanging it.
                        jdbc.sql("set local lock_timeout = '15s'").update();
                        jobs.claim(UUID.randomUUID());
                    }))
                    .isInstanceOf(QueryTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            holder.rollback();
        }
    }

    @Test
    @DisplayName("The limit applies to the work only: the outer transaction gets its own limit back")
    void previousLimitIsRestored() {
        String[] seen = new String[2];
        outer.executeWithoutResult(tx -> {
            jdbc.sql("set local statement_timeout = '45s'").update();
            seen[0] = StatementTimeouts.within(jdbc, Duration.ofMillis(1500), StatementTimeoutsIntegrationTest::limit);
            seen[1] = limit();
        });
        assertThat(seen).containsExactly("1500ms", "45s");
    }

    @Test
    @DisplayName("A statement over the limit fails with the timeout as the reported error")
    void statementOverTheLimitFails() {
        assertThatThrownBy(() -> outer.executeWithoutResult(tx -> StatementTimeouts.run(
                        jdbc,
                        Duration.ofMillis(100),
                        () -> jdbc.sql("select pg_sleep(2)").query().singleValue())))
                .isInstanceOf(QueryTimeoutException.class);
    }

    private static String limit() {
        return jdbc.sql("select current_setting('statement_timeout')")
                .query(String.class)
                .single();
    }
}
