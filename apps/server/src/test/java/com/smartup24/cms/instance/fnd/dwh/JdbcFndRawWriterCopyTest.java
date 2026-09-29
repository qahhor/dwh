package com.smartup24.cms.instance.fnd.dwh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.FndPref;
import com.smartup24.cms.instance.fnd.config.DwhDataSourceProperties;
import com.smartup24.cms.instance.fnd.config.FndDwhConfig;
import com.smartup24.cms.instance.fnd.load.FndLoad;
import com.smartup24.cms.instance.fnd.load.FndLoadService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Plan 10/10, item 3.9: raw is written by one streamed {@code COPY}, its text format escaped, its time bounded apart. */
class JdbcFndRawWriterCopyTest extends EmbeddedPostgresTest {

    private static final long MB = 1024 * 1024;

    @Autowired
    private FndRawWriter rawWriter;

    @Autowired
    private FndLoadService loads;

    @Autowired
    private FndActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    @Qualifier(FndPref.DWH)
    private JdbcClient dwhJdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void cleanDwh() {
        dwhJdbc.sql("delete from raw.rows").update();
    }

    @Test
    @DisplayName("3.9: значения с табуляцией, переводом строки, обратной косой и \\N ложатся как в файле")
    void copyKeepsAwkwardValuesAsRead() {
        long loadId = newLoad();
        Map<String, Object> awkward = new LinkedHashMap<>();
        awkward.put("tab", "a\tb");
        awkward.put("lines", "first\nsecond\r\nthird");
        awkward.put("slash", "C:\\TEST\\new");
        awkward.put("nullWord", "\\N");
        awkward.put("quote", "\"TEST\" 'x'");
        awkward.put("unicode", "Qoʻllash — ТЕСТ ✓");
        awkward.put("empty", null);
        List<FndRawRow> rows = List.of(
                new FndRawRow(1, "Лист\t1\\\n2\r3", 2, awkward),
                new FndRawRow(2, null, null, Map.of()),
                new FndRawRow(3, "\\N", 4, null));

        long written = rawWriter.copy(loadId, null, sink -> rows.forEach(sink));

        assertThat(written).isEqualTo(3);
        assertThat(rawWriter.count(loadId)).isEqualTo(3);
        List<FndRawRow> read = rawWriter.read(loadId);
        assertThat(read.get(0).sheet()).isEqualTo("Лист\t1\\\n2\r3");
        assertThat(read.get(0).sourceRowNo()).isEqualTo(2);
        // The application's JSON leaves null values out (non_null), as the row-by-row write always did: an empty cell
        // reads as absent, which the readers treat as null.
        Map<String, Object> stored = new LinkedHashMap<>(awkward);
        stored.remove("empty");
        assertThat(read.get(0).fields()).isEqualTo(stored);
        assertThat(read.get(0).fields().get("empty")).isNull();
        assertThat(read.get(1).sheet()).isNull();
        assertThat(read.get(1).sourceRowNo()).isNull();
        assertThat(read.get(1).fields()).isEmpty();
        assertThat(read.get(2).sheet()).isEqualTo("\\N");
        assertThat(read.get(2).fields()).isEmpty();
    }

    @Test
    @DisplayName("3.9: строка длиннее буфера копирования уходит целиком, соседние — своим порядком")
    void rowLongerThanTheBufferIsWritten() {
        long loadId = newLoad();
        String wide = "TEST".repeat(50_000);

        long written = rawWriter.copy(loadId, null, sink -> {
            sink.accept(new FndRawRow(1, null, null, Map.of("v", "short")));
            sink.accept(new FndRawRow(2, null, null, Map.of("v", wide)));
            for (int number = 3; number <= 2_000; number++) {
                sink.accept(new FndRawRow(number, null, number, Map.of("v", "TEST " + number)));
            }
        });

        assertThat(written).isEqualTo(2_000);
        assertThat(dwhJdbc.sql("select fields->>'v' from raw.rows where load_id = :id and row_no = 2")
                        .param("id", loadId)
                        .query(String.class)
                        .single())
                .isEqualTo(wide);
        assertThat(rawWriter.count(loadId)).isEqualTo(2_000);
        assertThat(rawWriter.count(-1)).isZero();
    }

    @Test
    @DisplayName("3.9: источник не дочитал файл — UncheckedIOException, в raw ничего, загрузка pending")
    void sourceIoFailureLeavesNothing() {
        long loadId = newLoad();

        assertThatThrownBy(() -> rawWriter.copy(loadId, null, sink -> {
                    for (int number = 1; number <= 1_500; number++) {
                        sink.accept(new FndRawRow(number, null, number, Map.of("n", number)));
                    }
                    throw new IOException("TEST файл оборвался");
                }))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("TEST файл оборвался");

        assertThat(rawWriter.count(loadId)).isZero();
        assertThat(loads.find(loadId).orElseThrow().status()).isEqualTo(FndLoad.PENDING);
    }

    @Test
    @DisplayName("3.9: повтор номера строки — отказ базы на COPY, в raw ничего")
    void serverRefusalRollsTheCopyBack() {
        long loadId = newLoad();

        assertThatThrownBy(() -> rawWriter.copy(loadId, null, sink -> {
                    sink.accept(new FndRawRow(1, null, null, Map.of()));
                    sink.accept(new FndRawRow(1, null, null, Map.of()));
                }))
                .isInstanceOf(DwhUnavailableException.class);

        assertThat(rawWriter.count(loadId)).isZero();
    }

    @Test
    @DisplayName("3.9: COPY дольше предела пула проходит под своим пределом; соединение возвращается с обычным")
    void copyHasItsOwnTimeout() throws Exception {
        try (HikariDataSource shortLimit = pool(Duration.ofSeconds(1))) {
            long loadId = newLoad();
            FndRawWriter patient =
                    new JdbcFndRawWriter(shortLimit, jdbc, tx.getTransactionManager(), json, Duration.ofSeconds(10));

            long written = patient.copy(loadId, null, slowRows(Duration.ofMillis(1_500)));

            assertThat(written).isEqualTo(2);
            try (Connection connection = shortLimit.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("show statement_timeout")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("1s");
            }

            long another = newLoad();
            FndRawWriter hasty =
                    new JdbcFndRawWriter(shortLimit, jdbc, tx.getTransactionManager(), json, Duration.ofMillis(500));
            assertThatThrownBy(() -> hasty.copy(another, null, slowRows(Duration.ofMillis(1_500))))
                    .isInstanceOf(DwhUnavailableException.class);
            assertThat(rawWriter.count(another)).isZero();
        }
    }

    @Test
    @DisplayName("3.9: писатель не копит строки — память на середине большой записи не растёт")
    void copyHoldsNoRows() {
        long loadId = newLoad();
        int rows = 40_000;
        String filler = "x".repeat(4_000);
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long[] heapAtProbe = {0};
        long baseline = liveHeap(memory);

        long written = rawWriter.copy(loadId, null, sink -> {
            for (int number = 1; number <= rows; number++) {
                // A fresh string per row: rows kept anywhere would add up to about 160 MB
                Map<String, Object> fields = new HashMap<>();
                fields.put("v", filler + number);
                sink.accept(new FndRawRow(number, null, number, fields));
                if (number == rows - 100) {
                    heapAtProbe[0] = liveHeap(memory);
                }
            }
        });

        assertThat(written).isEqualTo(rows);
        assertThat((heapAtProbe[0] - baseline) / MB)
                .as("heap held near the end of the write, MB")
                .isLessThan(48);
    }

    // ---------- вспомогательное ----------

    private static long liveHeap(MemoryMXBean memory) {
        memory.gc();
        memory.gc();
        return memory.getHeapMemoryUsage().getUsed();
    }

    private static FndRawSource slowRows(Duration pause) {
        return sink -> {
            sink.accept(new FndRawRow(1, null, null, Map.of("n", 1)));
            try {
                Thread.sleep(pause);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(stopped);
            }
            sink.accept(new FndRawRow(2, null, null, Map.of("n", 2)));
        };
    }

    private static HikariDataSource pool(Duration statementTimeout) {
        DwhDataSourceProperties props = new DwhDataSourceProperties(
                TestDatabases.jdbcUrl(TestDatabases.DWH_DB),
                TestDatabases.USER,
                "",
                Duration.ofSeconds(2),
                statementTimeout,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5));
        HikariDataSource dwh = (HikariDataSource) new FndDwhConfig().dwhDataSource(props);
        dwh.setMaximumPoolSize(1);
        return dwh;
    }

    private long newLoad() {
        return loads.begin(
                "src_test_copy",
                UUID.randomUUID(),
                LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-01-31"),
                "v1",
                actors.system());
    }
}
