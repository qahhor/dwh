package com.smartup24.cms.instance.fnd.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.8: the job queue leases a job in a short transaction and runs it outside, retries a failure with
 * a backoff up to a limit, takes a job back from a dead node, and two runners enqueue a scheduled job once.
 */
class FndJobQueueTest extends EmbeddedPostgresTest {

    private static final String SCHEDULE = "test.queue.scheduled";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    @AfterEach
    void cleanQueue() {
        jdbc.sql("delete from fnd_job_queue").update();
        jdbc.sql("delete from fnd_job_runs").update();
        jdbc.sql("delete from fnd_job_schedule where code = :code")
                .param("code", SCHEDULE)
                .update();
    }

    @Test
    @DisplayName("a failing job is retried until the limit, then marked failed and never taken again")
    void failingJobIsRetriedUntilTheLimit() {
        AtomicInteger calls = new AtomicInteger();
        FndJobRunner runner = runner(settings(3, Duration.ZERO, Duration.ofMinutes(1)), handler("test.q.fail", args -> {
            calls.incrementAndGet();
            throw new IllegalStateException("TEST failure " + calls.get());
        }));
        long id = enqueue("test.q.fail");

        assertThat(runner.runQueued()).isZero();

        assertThat(calls).hasValue(3);
        List<Map<String, Object>> runs = jdbc.sql(
                        "select status, attempt, error from fnd_job_runs where queue_id = :id order by id")
                .param("id", id)
                .query()
                .listOfRows();
        assertThat(runs).extracting(r -> r.get("status")).containsExactly("failed", "failed", "failed");
        assertThat(runs).extracting(r -> r.get("attempt")).containsExactly(1, 2, 3);
        assertThat((String) runs.get(2).get("error")).contains("TEST failure 3");
        Map<String, Object> row = queueRow(id);
        assertThat(row.get("attempts")).isEqualTo(3);
        assertThat(row.get("failed_at")).isNotNull();
        assertThat(row.get("locked_by")).isNull();

        assertThat(runner.runNext()).isEmpty();
        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("a job that fails and then succeeds leaves the queue; every attempt stays in the runs")
    void retriedJobSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        FndJobRunner runner =
                runner(settings(5, Duration.ZERO, Duration.ofMinutes(1)), handler("test.q.flaky", args -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new IllegalStateException("TEST flaky");
                    }
                }));
        long id = enqueue("test.q.flaky");

        assertThat(runner.runQueued()).isEqualTo(1);

        assertThat(jdbc.sql("select status from fnd_job_runs where queue_id = :id order by id")
                        .param("id", id)
                        .query(String.class)
                        .list())
                .containsExactly("failed", "failed", "done");
        assertThat(jdbc.sql("select count(*) from fnd_job_queue")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a failed attempt moves the job forward by the backoff, doubling up to its ceiling")
    void failedAttemptWaitsForTheBackoff() {
        FndJobProperties settings =
                new FndJobProperties(5, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(1));
        assertThat(settings.backoffAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.backoffAfter(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(settings.backoffAfter(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.backoffAfter(9)).isEqualTo(Duration.ofMinutes(2));

        FndJobRunner runner = runner(settings, handler("test.q.later", args -> {
            throw new IllegalStateException("TEST later");
        }));
        long id = enqueue("test.q.later");

        assertThat(runner.runNext()).contains(false);
        assertThat(runner.runNext()).as("the retry is not due yet").isEmpty();

        Double wait = jdbc.sql("select extract(epoch from next_run_at - now()) from fnd_job_queue where id = :id")
                .param("id", id)
                .query(Double.class)
                .single();
        assertThat(wait).isBetween(25.0, 31.0);
    }

    @Test
    @DisplayName("a long job holds no transaction while it works, and its lease is renewed")
    void longJobHoldsNoTransaction() throws Exception {
        List<Double> oldestTransactions = new ArrayList<>();
        List<OffsetDateTime> leases = new ArrayList<>();
        Duration lease = Duration.ofMillis(600);
        long[] id = new long[1];
        FndJobRunner runner = runner(settings(1, Duration.ZERO, lease), handler("test.q.long", args -> {
            for (int second = 0; second < 3; second++) {
                sleep(Duration.ofSeconds(1));
                // The age of the oldest transaction of this database, this query's own excluded
                oldestTransactions.add(jdbc.sql("""
                                select coalesce(max(extract(epoch from clock_timestamp() - xact_start)), 0)
                                  from pg_stat_activity
                                 where datname = current_database() and pid <> pg_backend_pid()
                                   and backend_type = 'client backend'
                                """).query(Double.class).single());
                leases.add(jdbc.sql("select locked_until from fnd_job_queue where id = :id")
                        .param("id", id[0])
                        .query(OffsetDateTime.class)
                        .single());
            }
        }));
        id[0] = enqueue("test.q.long");
        // Load around it: other runners claim and finish short jobs while the long one works
        FndJobRunner others = runner(settings(1, Duration.ZERO, lease), handler("test.q.short", args -> {}));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<Integer> longJob = pool.submit(runner::runQueued);
            List<Future<Integer>> load = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                load.add(pool.submit(() -> {
                    int done = 0;
                    for (int n = 0; n < 20; n++) {
                        enqueue("test.q.short");
                        done += others.runQueued();
                        sleep(Duration.ofMillis(50));
                    }
                    return done;
                }));
            }
            assertThat(longJob.get(60, TimeUnit.SECONDS)).isEqualTo(1);
            for (Future<Integer> worker : load) {
                assertThat(worker.get(60, TimeUnit.SECONDS)).isPositive();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(oldestTransactions)
                .as("seconds of the oldest open transaction while the job ran 1, 2 and 3 seconds")
                .hasSize(3)
                .allSatisfy(age -> assertThat(age).isLessThan(1.0));
        assertThat(leases).hasSize(3);
        assertThat(leases.get(2)).as("the lease was renewed").isAfter(leases.get(0));
    }

    @Test
    @DisplayName("a job whose node died is taken again once its lease runs out; a live lease is left alone")
    void expiredLeaseIsTakenAgain() {
        AtomicInteger calls = new AtomicInteger();
        FndJobRunner runner = runner(
                settings(3, Duration.ZERO, Duration.ofMinutes(1)),
                handler("test.q.dead", args -> calls.incrementAndGet()));
        long dead = enqueue("test.q.dead");
        long alive = enqueue("test.q.dead");
        jdbc.sql("update fnd_job_queue set attempts = 1, locked_by = 'TEST dead node',"
                        + " locked_until = now() - interval '1 second' where id = :id")
                .param("id", dead)
                .update();
        jdbc.sql("insert into fnd_job_runs (queue_id, handler, args, status, attempt)"
                        + " values (:id, 'test.q.dead', '{}'::jsonb, 'running', 1)")
                .param("id", dead)
                .update();
        jdbc.sql("update fnd_job_queue set attempts = 1, locked_by = 'TEST live node',"
                        + " locked_until = now() + interval '1 minute' where id = :id")
                .param("id", alive)
                .update();

        assertThat(runner.runQueued()).isEqualTo(1);

        assertThat(calls).hasValue(1);
        assertThat(jdbc.sql("select status || ':' || attempt || ':' || coalesce(error, '') from fnd_job_runs"
                                + " where queue_id = :id order by id")
                        .param("id", dead)
                        .query(String.class)
                        .list())
                .containsExactly("failed:1:lease expired", "done:2:");
        assertThat(queueRow(alive).get("locked_by")).isEqualTo("TEST live node");
    }

    @Test
    @DisplayName("a job taken back from a dead node with no attempt left is marked failed without running")
    void expiredLeaseOnTheLastAttemptFails() {
        AtomicInteger calls = new AtomicInteger();
        FndJobRunner runner = runner(
                settings(2, Duration.ZERO, Duration.ofMinutes(1)),
                handler("test.q.last", args -> calls.incrementAndGet()));
        long id = enqueue("test.q.last");
        jdbc.sql("update fnd_job_queue set attempts = 2, locked_by = 'TEST dead node',"
                        + " locked_until = now() - interval '1 second' where id = :id")
                .param("id", id)
                .update();

        assertThat(runner.runQueued()).isZero();

        assertThat(calls).hasValue(0);
        assertThat(queueRow(id).get("failed_at")).isNotNull();
    }

    @Test
    @DisplayName("two runners ticking together enqueue a due scheduled job exactly once")
    void twoRunnersEnqueueOnce() throws Exception {
        jdbc.sql("insert into fnd_job_schedule (code, handler, interval_sec) values (:code, :code, 3600)")
                .param("code", SCHEDULE)
                .update();
        FndJobHandler handler = handler(SCHEDULE, args -> {});
        FndJobRunner first = runner(FndJobProperties.defaults(), handler);
        FndJobRunner second = runner(FndJobProperties.defaults(), handler);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= 10; round++) {
                jdbc.sql("update fnd_job_schedule set last_enqueued = null where code = :code")
                        .param("code", SCHEDULE)
                        .update();
                CyclicBarrier together = new CyclicBarrier(2);
                Future<Integer> a = pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    return first.enqueueDue();
                });
                Future<Integer> b = pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    return second.enqueueDue();
                });
                assertThat(a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS))
                        .as("jobs enqueued in round %d", round)
                        .isEqualTo(1);
                assertThat(jdbc.sql("select count(*) from fnd_job_queue where schedule_code = :code")
                                .param("code", SCHEDULE)
                                .query(Long.class)
                                .single())
                        .isEqualTo(round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("while another runner holds the scheduling lock, a runner enqueues nothing and does not wait")
    void schedulingLockHeldElsewhere() throws Exception {
        jdbc.sql("insert into fnd_job_schedule (code, handler, interval_sec) values (:code, :code, 3600)")
                .param("code", SCHEDULE)
                .update();
        FndJobRunner runner = runner(FndJobProperties.defaults(), handler(SCHEDULE, args -> {}));
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                statement.execute("select pg_advisory_xact_lock(hashtext('fnd.jobs.enqueue_due'))");
            }
            long begun = System.nanoTime();
            assertThat(runner.enqueueDue()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - begun)).isLessThan(Duration.ofSeconds(1));
            other.rollback();
        }
        assertThat(runner.enqueueDue()).isEqualTo(1);
    }

    @Test
    @DisplayName("a runner leaves schedules and jobs of handlers it does not know")
    void unknownHandlersAreLeftAlone() {
        jdbc.sql("insert into fnd_job_schedule (code, handler, interval_sec) values (:code, 'test.q.nobody', 3600)")
                .param("code", SCHEDULE)
                .update();
        FndJobRunner runner = runner(FndJobProperties.defaults(), handler("test.q.known", args -> {}));
        long id = enqueue("test.q.nobody");

        assertThat(runner.enqueueDue()).isZero();
        assertThat(runner.runQueued()).isZero();
        assertThat(queueRow(id).get("attempts")).isEqualTo(0);
    }

    // ---------- helpers ----------

    private static FndJobProperties settings(int maxAttempts, Duration backoff, Duration lease) {
        return new FndJobProperties(maxAttempts, backoff, backoff, lease);
    }

    private FndJobRunner runner(FndJobProperties settings, FndJobHandler... handlers) {
        return new FndJobRunner(jdbc, json, transactions, List.of(handlers), settings);
    }

    private static FndJobHandler handler(String code, Consumer<Map<String, Object>> body) {
        return new FndJobHandler() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public void run(Map<String, Object> args) {
                body.accept(args);
            }
        };
    }

    private long enqueue(String handler) {
        return jdbc.sql("insert into fnd_job_queue (handler) values (:h) returning id")
                .param("h", handler)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> queueRow(long id) {
        return jdbc.sql("select attempts, locked_by, failed_at from fnd_job_queue where id = :id")
                .param("id", id)
                .query()
                .singleRow();
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
