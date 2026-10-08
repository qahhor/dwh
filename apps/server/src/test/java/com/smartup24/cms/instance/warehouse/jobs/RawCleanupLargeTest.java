package com.smartup24.cms.instance.warehouse.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.warehouse.RawTables;
import com.smartup24.cms.instance.warehouse.WarehousePref;
import com.smartup24.cms.instance.warehouse.api.RawRow;
import com.smartup24.cms.instance.warehouse.api.RawWriter;
import com.smartup24.cms.instance.warehouse.load.WarehouseLoadService;
import com.smartup24.cms.instance.warehouse.raw.RawPartitions;
import com.smartup24.cms.platform.api.actor.AuditActor;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 7.8, acceptance: the cleanup of a failed load of a million rows takes under a second and leaves
 * {@code n_dead_tup} of the raw layer near zero, because it drops the load's partition instead of deleting rows.
 *
 * <p>Tagged {@code warehouse-large}: writing a million rows through {@code COPY} takes a while, so the everyday suite skips
 * it; {@link RawPartitionCleanupTest} proves the same mechanics on small loads. The nightly {@code load} job runs it:
 *
 * <pre>
 * mvn -pl apps/server test -Pwarehouse-large [-Dwarehouse.large.rows=1000000]
 * </pre>
 */
@Tag("warehouse-large")
class RawCleanupLargeTest extends EmbeddedPostgresTest {

    private static final Logger log = LoggerFactory.getLogger(RawCleanupLargeTest.class);
    private static final int ROWS = Integer.getInteger("warehouse.large.rows", 1_000_000);
    private static final int KEPT_ROWS = 10_000;
    private static final Duration LIMIT = Duration.ofSeconds(1);
    private static final String SOURCE = "src_test_warehouse_large";

    @Autowired
    private WarehouseLoadService loads;

    @Autowired
    private RawWriter rawWriter;

    @Autowired
    private LoadCleanupJob cleanup;

    @Autowired
    private MdAuditActors actors;

    @Autowired
    @Qualifier(WarehousePref.QUALIFIER)
    private JdbcClient dwhJdbc;

    @Test
    @DisplayName("7.8: cleanup of a 1M-row failed load < 1 s, n_dead_tup of raw about 0 afterwards")
    void cleanupOfAMillionRowsIsFastAndLeavesNoDeadTuples() throws InterruptedException {
        AuditActor actor = actors.system();
        RawTables.clear(dwhJdbc);
        long kept = loads.begin(
                SOURCE, UUID.randomUUID(), LocalDate.parse("2031-01-01"), LocalDate.parse("2031-01-31"), "v1", actor);
        rawWriter.copy(kept, null, sink -> fill(sink, KEPT_ROWS));
        loads.apply(kept, KEPT_ROWS, KEPT_ROWS, 0, actor);

        long failed = loads.begin(
                SOURCE, UUID.randomUUID(), LocalDate.parse("2031-02-01"), LocalDate.parse("2031-02-28"), "v1", actor);
        long copyStarted = System.nanoTime();
        long written = rawWriter.copy(failed, UUID.randomUUID(), sink -> fill(sink, ROWS));
        Duration copyTime = Duration.ofNanos(System.nanoTime() - copyStarted);
        assertThat(written).isEqualTo(ROWS);
        loads.fail(failed, "TEST failure", actor);

        long started = System.nanoTime();
        cleanup.run(Map.of());
        Duration cleanupTime = Duration.ofNanos(System.nanoTime() - started);

        assertThat(dwhJdbc.sql("select to_regclass(:name) is null")
                        .param("name", RawPartitions.table(failed))
                        .query(Boolean.class)
                        .single())
                .as("the failed load's partition is gone")
                .isTrue();
        assertThat(rawWriter.count(kept)).isEqualTo(KEPT_ROWS);
        long deadTuples = deadTuplesOfRaw(kept);
        log.info(
                "warehouse_large rows={} copy_ms={} cleanup_ms={} n_dead_tup={}",
                ROWS,
                copyTime.toMillis(),
                cleanupTime.toMillis(),
                deadTuples);
        assertThat(cleanupTime).as("cleanup of %d rows", ROWS).isLessThan(LIMIT);
        assertThat(deadTuples).as("n_dead_tup of raw after the cleanup").isLessThanOrEqualTo(ROWS / 10_000);
    }

    /** Sum of {@code n_dead_tup} over raw once the statistics of an {@code ANALYZE} have reached the reader. */
    private long deadTuplesOfRaw(long kept) throws InterruptedException {
        dwhJdbc.sql("analyze raw.rows").update();
        String partition = RawPartitions.name(kept);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean analyzed = dwhJdbc.sql("select last_analyze is not null from pg_stat_user_tables"
                            + " where schemaname = 'raw' and relname = :name")
                    .param("name", partition)
                    .query(Boolean.class)
                    .optional()
                    .orElse(false);
            if (analyzed) {
                break;
            }
            Thread.sleep(200);
        }
        return dwhJdbc.sql("select coalesce(sum(n_dead_tup), 0) from pg_stat_user_tables where schemaname = 'raw'")
                .query(Long.class)
                .single();
    }

    private static void fill(Consumer<RawRow> sink, int count) {
        for (int number = 1; number <= count; number++) {
            sink.accept(new RawRow(
                    number,
                    "Sheet1",
                    number + 1,
                    Map.of("sku", "TEST-" + number, "qty", number % 100, "price", number % 997 + 0.5)));
        }
    }
}
