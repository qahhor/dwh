package com.smartup24.cms.instance.warehouse.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.jobs.config.JobProperties;
import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.jobs.runner.JobSwitch;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.warehouse.WarehousePref;
import com.smartup24.cms.instance.warehouse.api.RawRow;
import com.smartup24.cms.instance.warehouse.api.RawWriter;
import com.smartup24.cms.instance.warehouse.load.WarehouseLoadService;
import com.smartup24.cms.platform.api.actor.AuditActor;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The foundation maintenance jobs: cleaning up failed loads and reconciling the two databases. */
class WarehouseMaintenanceJobsTest extends EmbeddedPostgresTest {

    private static final String SOURCE = "src_test_jobs";

    @Autowired
    private WarehouseLoadService loads;

    @Autowired
    private RawWriter rawWriter;

    @Autowired
    private JobRunner jobs;

    @Autowired
    private MdAuditActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    @Qualifier(WarehousePref.QUALIFIER)
    private JdbcClient dwhJdbc;

    @Autowired
    private TransactionTemplate tx;

    private AuditActor actor;

    @BeforeEach
    void cleanState() {
        actor = actors.system();
        tx.executeWithoutResult(status -> {
            actors.apply(actor);
            jdbc.sql("select set_config('dwh.maintenance', 'on', true)")
                    .query(String.class)
                    .single();
            jdbc.sql("delete from fnd_load_log").update();
            jdbc.sql("update fnd_loads set superseded_by = null").update();
            jdbc.sql("delete from fnd_loads").update();
            jdbc.sql("delete from security_events where event_type = :event")
                    .param("event", CrossDatabaseCheckJob.EVENT)
                    .update();
            jdbc.sql("delete from fnd_job_queue").update();
            jdbc.sql("delete from fnd_job_runs").update();
            jdbc.sql("update fnd_job_schedule set last_enqueued = null").update();
        });
        dwhJdbc.sql("delete from raw.rows").update();
    }

    @Test
    @DisplayName("AC-31: сид расписания содержит оба обработчика основы")
    void scheduleIsSeeded() {
        List<String> codes = jdbc.sql("select code from fnd_job_schedule where code in (:cleanup, :check)")
                .param("cleanup", LoadCleanupJob.CODE)
                .param("check", CrossDatabaseCheckJob.CODE)
                .query(String.class)
                .list();
        assertThat(codes).containsExactlyInAnyOrder(LoadCleanupJob.CODE, CrossDatabaseCheckJob.CODE);
    }

    @Test
    @DisplayName("AC-31: очистка удаляет строки неудачной загрузки и не трогает применённую")
    void cleanupRemovesOnlyFailedRows() {
        long failed = loads.begin(
                SOURCE, UUID.randomUUID(), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), "v1", actor);
        rawWriter.write(failed, null, rows(100));
        loads.fail(failed, "сбой TEST", actor);

        long applied = loads.begin(
                SOURCE, UUID.randomUUID(), LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28"), "v1", actor);
        rawWriter.write(applied, null, rows(5));
        loads.apply(applied, 5, 5, 0, actor);

        jobs.enqueue(LoadCleanupJob.CODE);
        assertThat(jobs.runQueued()).isEqualTo(1);

        assertThat(rawWriter.read(failed)).isEmpty();
        assertThat(rawWriter.read(applied)).hasSize(5);
        assertThat(jdbc.sql("select status from fnd_job_runs where handler = :h")
                        .param("h", LoadCleanupJob.CODE)
                        .query(String.class)
                        .list())
                .containsExactly("done");
        assertThat(jdbc.sql("select count(*) from fnd_job_queue")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("AC-31: сверка находит строки raw без загрузки и без файла и пишет xdb_mismatch")
    void xdbCheckReportsOrphans() {
        long applied = loads.begin(
                SOURCE, UUID.randomUUID(), LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"), "v1", actor);
        rawWriter.write(applied, null, rows(2));
        loads.apply(applied, 2, 2, 0, actor);

        UUID orphanFile = UUID.randomUUID();
        dwhJdbc.sql("insert into raw.rows (load_id, row_no, fields) values (999999, 1, '{}'::jsonb)")
                .update();
        dwhJdbc.sql("insert into raw.rows (load_id, source_file_id, row_no, fields)"
                        + " values (:load, :file, 99, '{}'::jsonb)")
                .param("load", applied)
                .param("file", orphanFile)
                .update();

        jobs.enqueue(CrossDatabaseCheckJob.CODE);
        assertThat(jobs.runQueued()).isEqualTo(1);

        List<String> events = jdbc.sql("select details::text from security_events where event_type = :event")
                .param("event", CrossDatabaseCheckJob.EVENT)
                .query(String.class)
                .list();
        assertThat(events).hasSize(2);
        assertThat(events).anyMatch(details -> details.contains("999999"));
        assertThat(events).anyMatch(details -> details.contains(orphanFile.toString()));
        // The reconciliation does not touch the data
        assertThat(dwhJdbc.sql("select count(*) from raw.rows")
                        .query(Long.class)
                        .single())
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("AC-31: за один вызов — 3 сироты найдены, 2 чистые загрузки не помечены")
    void xdbCheckBatchFindsOnlyOrphans() {
        long first = loads.begin(
                SOURCE, UUID.randomUUID(), LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"), "v1", actor);
        rawWriter.write(first, null, rows(3));
        loads.apply(first, 3, 3, 0, actor);
        long second = loads.begin(
                SOURCE, UUID.randomUUID(), LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31"), "v1", actor);
        rawWriter.write(second, null, rows(2));
        loads.apply(second, 2, 2, 0, actor);

        UUID orphanFile = UUID.randomUUID();
        dwhJdbc.sql("insert into raw.rows (load_id, row_no, fields) values (999991, 1, '{}'::jsonb)")
                .update();
        dwhJdbc.sql("insert into raw.rows (load_id, row_no, fields) values (999992, 1, '{}'::jsonb)")
                .update();
        dwhJdbc.sql("insert into raw.rows (load_id, source_file_id, row_no, fields)"
                        + " values (:load, :file, 99, '{}'::jsonb)")
                .param("load", first)
                .param("file", orphanFile)
                .update();

        jobs.enqueue(CrossDatabaseCheckJob.CODE);
        assertThat(jobs.runQueued()).isEqualTo(1);

        List<String> events = jdbc.sql("select details::text from security_events where event_type = :event")
                .param("event", CrossDatabaseCheckJob.EVENT)
                .query(String.class)
                .list();
        assertThat(events).hasSize(3);
        assertThat(events).anyMatch(details -> details.contains("999991"));
        assertThat(events).anyMatch(details -> details.contains("999992"));
        assertThat(events).anyMatch(details -> details.contains(orphanFile.toString()));
        assertThat(events).noneMatch(details -> details.contains("\"load_id\": " + first + "}"));
        assertThat(events).noneMatch(details -> details.contains("\"load_id\": " + second + "}"));
        assertThat(jdbc.sql("select status from fnd_job_runs where handler = :h")
                        .param("h", CrossDatabaseCheckJob.CODE)
                        .query(String.class)
                        .list())
                .containsExactly("done");
        assertThat(dwhJdbc.sql("select count(*) from raw.rows")
                        .query(Long.class)
                        .single())
                .isEqualTo(8L);
    }

    @Test
    @DisplayName("AC-7: задания попадают в очередь по расписанию и исполняются без планировщика Spring")
    void scheduleEnqueuesDueJobs() {
        assertThat(jobs.enqueueDue()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("select count(*) from fnd_job_queue")
                        .query(Long.class)
                        .single())
                .isGreaterThanOrEqualTo(2L);
        // Right after enqueueing, the next run is not due yet
        assertThat(jobs.enqueueDue()).isZero();
        assertThat(jobs.runQueued()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("select count(*) from fnd_job_runs where status = 'done'")
                        .query(Long.class)
                        .single())
                .isGreaterThanOrEqualTo(2L);
    }

    // ---------- the queue under load, the failed path, the switch ----------

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PlatformTransactionManager transactions;

    /** The application's switch: it reads jobs_enabled through the settings' owner (plan 10/10, item 4.2). */
    @Autowired
    private JobSwitch jobSwitch;

    /** A worker with test handlers: no schedule beans are needed, the queue takes any handler code. */
    private JobRunner testRunner(JobHandler... handlers) {
        return new JobRunner(jdbc, json, transactions, List.of(handlers));
    }

    private static JobHandler handler(String code, Consumer<Map<String, Object>> body) {
        return new JobHandler() {
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

    private long enqueueRaw(String handlerCode, String args) {
        return jdbc.sql("insert into fnd_job_queue (handler, args) values (:h, cast(:a as jsonb)) returning id")
                .param("h", handlerCode)
                .param("a", args)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName(
            "AC-7: два воркера берут разные задания (for update skip locked), третий вызов пуст и не ждёт дольше 1 с")
    void twoWorkersTakeDifferentJobs() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        JobRunner runner = testRunner(handler("test.block", args -> {
            started.countDown();
            try {
                assertThat(gate.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
        long first = enqueueRaw("test.block", "{}");
        long second = enqueueRaw("test.block", "{}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var c1 = pool.submit(runner::runNext);
            var c2 = pool.submit(runner::runNext);
            assertThat(started.await(30, TimeUnit.SECONDS))
                    .as("оба воркера держат по заданию, не дожидаясь чужого коммита")
                    .isTrue();
            long begun = System.nanoTime();
            assertThat(runner.runNext())
                    .as("третий вызов при двух занятых заданиях")
                    .isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - begun)).isLessThan(Duration.ofSeconds(1));
            gate.countDown();
            assertThat(c1.get(30, TimeUnit.SECONDS)).contains(true);
            assertThat(c2.get(30, TimeUnit.SECONDS)).contains(true);
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("select queue_id from fnd_job_runs where handler = 'test.block' and status = 'done'")
                        .query(Long.class)
                        .list())
                .containsExactlyInAnyOrder(first, second);
        assertThat(jdbc.sql("select count(*) from fnd_job_queue")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("AC-7: исключение обработчика — запуск failed с текстом ошибки и args, задание ждёт повтора;"
            + " незнакомый обработчик воркер не берёт")
    void handlerFailureIsRecorded() {
        JobRunner runner = testRunner(
                handler("test.fail", args -> {
                    throw new IllegalStateException("boom TEST " + args.get("k"));
                }),
                handler(
                        "test.sqlfail",
                        args -> jdbc.sql("select 1 from fnd_no_such_table_test")
                                .query()
                                .listOfRows()));
        enqueueRaw("test.fail", "{\"k\": \"v\"}");
        enqueueRaw("test.sqlfail", "{}");
        long unknown = enqueueRaw("test.unknown", "{}");

        assertThat(runner.runQueued()).isZero();

        List<Map<String, Object>> runs = jdbc.sql("select handler, status, error, args::text as args, attempt"
                        + " from fnd_job_runs where handler like 'test.%' order by id")
                .query()
                .listOfRows();
        assertThat(runs).extracting(r -> r.get("status")).containsExactly("failed", "failed");
        assertThat(runs).extracting(r -> r.get("attempt")).containsExactly(1, 1);
        assertThat((String) runs.get(0).get("error")).contains("boom TEST v");
        assertThat((String) runs.get(0).get("args")).contains("\"k\"").contains("\"v\"");
        assertThat((String) runs.get(1).get("error")).contains("fnd_no_such_table_test");
        // Plan 10/10, item 3.8: the failed jobs wait for their retry; the unknown one is left for a node that knows it
        assertThat(jdbc.sql("select count(*) from fnd_job_queue where attempts = 1 and next_run_at > now()"
                                + " and locked_by is null and failed_at is null")
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
        assertThat(jdbc.sql("select attempts from fnd_job_queue where id = :id")
                        .param("id", unknown)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("AC-7: jobs_enabled=false в md_settings каркаса — воркер ничего не берёт; после включения — выполняет")
    void jobsEnabledSwitch() {
        JobRunner runner = new JobRunner(
                jdbc,
                json,
                transactions,
                List.of(handler("test.noop", args -> {})),
                JobProperties.defaults(),
                jobSwitch);
        enqueueRaw("test.noop", "{}");
        jdbc.sql("insert into md_settings (user_id, key, value) values (null, :key, 'false')")
                .param("key", JobRunner.JOBS_ENABLED_KEY)
                .update();
        try {
            assertThat(runner.runNext()).isEmpty();
            assertThat(jdbc.sql("select count(*) from fnd_job_queue")
                            .query(Long.class)
                            .single())
                    .isEqualTo(1L);
            assertThat(jdbc.sql("select count(*) from fnd_job_runs")
                            .query(Long.class)
                            .single())
                    .isZero();
        } finally {
            jdbc.sql("delete from md_settings where user_id is null and key = :key")
                    .param("key", JobRunner.JOBS_ENABLED_KEY)
                    .update();
        }
        assertThat(runner.runQueued()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from fnd_job_queue")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private static List<RawRow> rows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(number -> new RawRow(number, null, number, Map.of("n", number)))
                .toList();
    }
}
