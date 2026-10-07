package com.smartup24.cms.instance.warehouse.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.RawTables;
import com.smartup24.cms.instance.warehouse.WarehousePref;
import com.smartup24.cms.instance.warehouse.api.RawRow;
import com.smartup24.cms.instance.warehouse.api.RawSource;
import com.smartup24.cms.instance.warehouse.api.RawWriter;
import com.smartup24.cms.instance.warehouse.load.WarehouseLoadService;
import com.smartup24.cms.instance.warehouse.raw.RawPartitions;
import com.smartup24.cms.instance.warehouse.repository.RawRowRepository;
import com.smartup24.cms.instance.warehouse.repository.RawRowRepository.RawPartition;
import com.smartup24.cms.platform.api.actor.AuditActor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 7.8: raw is partitioned by load; the writer creates and attaches the partition of its load, the
 * cleanup of a failed load detaches and drops it and never deletes a row (a row trigger forbids {@code DELETE}, so a
 * cleanup that deleted would fail here), and the cleanup of one load does not wait for the copy of another.
 */
class RawPartitionCleanupTest extends EmbeddedPostgresTest {

    private static final String SOURCE = "src_test_partitions";

    @Autowired
    private WarehouseLoadService loads;

    @Autowired
    private RawWriter rawWriter;

    @Autowired
    private RawRowRepository raw;

    @Autowired
    private LoadCleanupJob cleanup;

    @Autowired
    private MdAuditActors actors;

    @Autowired
    @Qualifier(WarehousePref.QUALIFIER)
    private JdbcClient dwhJdbc;

    private AuditActor actor;
    private int period;

    @BeforeEach
    void cleanRaw() {
        actor = actors.system();
        RawTables.clear(dwhJdbc);
    }

    @Test
    @DisplayName("7.8: the cleanup drops the failed load's partition, keeps the applied one; rows cannot be deleted")
    void cleanupDropsThePartitionOfAFailedLoad() {
        long failed = newLoad();
        rawWriter.write(failed, null, rows(100));
        loads.fail(failed, "TEST failure", actor);
        long applied = newLoad();
        rawWriter.write(applied, null, rows(5));
        loads.apply(applied, 5, 5, 0, actor);

        assertThat(raw.partitions())
                .containsExactlyInAnyOrder(
                        new RawPartition(failed, true, false), new RawPartition(applied, true, false));
        assertThat(triggerOf(applied))
                .as("the immutability trigger is cloned on attach")
                .isTrue();
        assertThatThrownBy(() -> dwhJdbc.sql("delete from raw.rows where load_id = :id")
                        .param("id", applied)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("raw_rows_immutable");

        cleanup.run(Map.of());

        assertThat(raw.partitions()).containsExactly(new RawPartition(applied, true, false));
        assertThat(exists(failed)).isFalse();
        assertThat(rawWriter.count(failed)).isZero();
        assertThat(rawWriter.count(applied)).isEqualTo(5);
        // Nothing left to do: a second run drops nothing and fails nothing
        cleanup.run(Map.of());
        assertThat(rawWriter.count(applied)).isEqualTo(5);
    }

    @Test
    @DisplayName("7.8: a failed write leaves no partition; an attached empty partition takes a later write")
    void writeCreatesItsPartitionAtomically() {
        long broken = newLoad();
        assertThatThrownBy(() -> rawWriter.copy(broken, null, sink -> {
                    sink.accept(new RawRow(1, null, 1, Map.of("n", 1)));
                    throw new IOException("TEST file cut short");
                }))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(exists(broken)).isFalse();

        long empty = newLoad();
        assertThat(rawWriter.copy(empty, null, sink -> {})).isZero();
        assertThat(raw.partitions()).containsExactly(new RawPartition(empty, true, false));
        assertThat(rawWriter.copy(empty, null, sink -> rows(3).forEach(sink))).isEqualTo(3);
        assertThat(rawWriter.count(empty)).isEqualTo(3);
    }

    @Test
    @DisplayName("7.8: a partition left detached by an interrupted cleanup is dropped by the next one")
    void leftoverDetachedPartitionIsDropped() {
        long failed = newLoad();
        rawWriter.write(failed, null, rows(10));
        loads.fail(failed, "TEST failure", actor);
        dwhJdbc.sql("alter table raw.rows detach partition " + RawPartitions.table(failed))
                .update();
        assertThat(raw.partitions()).containsExactly(new RawPartition(failed, false, false));

        cleanup.run(Map.of());

        assertThat(exists(failed)).isFalse();
    }

    @Test
    @DisplayName("7.8: the cleanup of one load does not wait for a copy of another still in progress")
    void cleanupRunsBesideAnotherLoadsCopy() throws Exception {
        long failed = newLoad();
        rawWriter.write(failed, null, rows(50));
        loads.fail(failed, "TEST failure", actor);
        long writing = newLoad();
        CountDownLatch halfway = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RawSource paused = sink -> {
            rows(10).forEach(sink);
            halfway.countDown();
            await(release);
            for (int number = 11; number <= 20; number++) {
                sink.accept(new RawRow(number, null, number, Map.of("n", number)));
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Long> copy = pool.submit(() -> rawWriter.copy(writing, null, paused));
            assertThat(halfway.await(30, TimeUnit.SECONDS)).isTrue();

            CompletableFuture.runAsync(() -> cleanup.run(Map.of())).get(20, TimeUnit.SECONDS);

            assertThat(exists(failed)).isFalse();
            assertThat(copy.isDone()).as("the other copy is still open").isFalse();
            release.countDown();
            assertThat(copy.get(30, TimeUnit.SECONDS)).isEqualTo(20);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(rawWriter.count(writing)).isEqualTo(20);
        assertThat(raw.partitions()).containsExactly(new RawPartition(writing, true, false));
    }

    @Test
    @DisplayName("7.8: two loads are written in parallel, each into its own partition")
    void twoLoadsAreWrittenInParallel() throws Exception {
        long first = newLoad();
        long second = newLoad();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> a = pool.submit(
                    () -> rawWriter.copy(first, null, sink -> rows(2_000).forEach(sink)));
            Future<Long> b = pool.submit(
                    () -> rawWriter.copy(second, null, sink -> rows(3_000).forEach(sink)));
            assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(2_000);
            assertThat(b.get(60, TimeUnit.SECONDS)).isEqualTo(3_000);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rawWriter.count(first)).isEqualTo(2_000);
        assertThat(rawWriter.count(second)).isEqualTo(3_000);
        assertThat(dwhJdbc.sql("explain select count(*) from raw.rows where load_id = :id")
                        .param("id", second)
                        .query(String.class)
                        .list())
                .as("the planner prunes to the load's partition")
                .anyMatch(line -> line.contains(RawPartitions.name(second)))
                .noneMatch(line -> line.contains(RawPartitions.name(first)));
    }

    private boolean exists(long loadId) {
        return dwhJdbc.sql("select to_regclass(:name) is not null")
                .param("name", RawPartitions.table(loadId))
                .query(Boolean.class)
                .single();
    }

    private boolean triggerOf(long loadId) {
        return dwhJdbc.sql("select count(*) > 0 from pg_trigger where tgrelid = to_regclass(:name)"
                        + " and tgname = 'raw_rows_immutable'")
                .param("name", RawPartitions.table(loadId))
                .query(Boolean.class)
                .single();
    }

    private long newLoad() {
        period++;
        LocalDate from = LocalDate.parse("2030-01-01").plusMonths(period);
        return loads.begin(SOURCE, UUID.randomUUID(), from, from.plusDays(27), "v1", actor);
    }

    private static List<RawRow> rows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(number -> new RawRow(number, null, number, Map.of("n", number)))
                .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("TEST latch not released in " + Duration.ofSeconds(60));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
