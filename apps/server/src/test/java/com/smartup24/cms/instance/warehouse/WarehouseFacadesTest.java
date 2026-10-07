package com.smartup24.cms.instance.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartup24.cms.instance.common.error.ConstraintCode;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.RawTables;
import com.smartup24.cms.instance.warehouse.api.RawRow;
import com.smartup24.cms.instance.warehouse.api.RawWriter;
import com.smartup24.cms.instance.warehouse.api.WarehouseError;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoad;
import com.smartup24.cms.instance.warehouse.api.WarehouseUnavailableException;
import com.smartup24.cms.instance.warehouse.load.WarehouseLoadService;
import com.smartup24.cms.instance.warehouse.mart.MartReader;
import com.smartup24.cms.instance.warehouse.raw.JdbcRawWriter;
import com.smartup24.cms.platform.api.actor.AuditActor;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The facades of the second database: writing raw rows and reading marts. */
class WarehouseFacadesTest extends EmbeddedPostgresTest {

    private static final String SOURCE = "src_test_dwh";

    @Autowired
    private RawWriter rawWriter;

    @Autowired
    private MartReader martReader;

    @Autowired
    private WarehouseLoadService loads;

    @Autowired
    private MdAuditActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    @Qualifier(WarehousePref.QUALIFIER)
    private JdbcClient dwhJdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private TransactionTemplate tx;

    private AuditActor actor;

    @BeforeEach
    void cleanDwh() {
        actor = actors.system();
        tx.executeWithoutResult(status -> {
            actors.apply(actor);
            jdbc.sql("select set_config('dwh.maintenance', 'on', true)")
                    .query(String.class)
                    .single();
            jdbc.sql("delete from fnd_load_log").update();
            jdbc.sql("update fnd_loads set superseded_by = null").update();
            jdbc.sql("delete from fnd_loads").update();
        });
        RawTables.clear(dwhJdbc);
        dwhJdbc.sql("delete from cache.items").update();
        dwhJdbc.sql("delete from cache.generations").update();
    }

    @Test
    @DisplayName("AC-33: строки пишутся с load_id и читаются как записаны; чужой статус загрузки — отказ")
    void writeAndRead() {
        long loadId = newLoad();
        UUID fileId = newFile();
        rawWriter.write(
                loadId,
                fileId,
                List.of(
                        new RawRow(1, "Лист1", 10, Map.of("code", "TEST-1")),
                        new RawRow(2, "Лист1", 11, Map.of("code", "TEST-2")),
                        new RawRow(3, null, null, Map.of("code", "TEST-3"))));

        List<RawRow> rows = rawWriter.read(loadId);
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().sheet()).isEqualTo("Лист1");
        assertThat(rows.getFirst().sourceRowNo()).isEqualTo(10);
        assertThat(rows.getFirst().fields()).containsEntry("code", "TEST-1");
        assertThat(rows.get(2).sheet()).isNull();
        assertThat(dwhJdbc.sql("select count(*) from raw.rows where load_id = :id and source_file_id = :file"
                                + " and loaded_at is not null")
                        .param("id", loadId)
                        .param("file", fileId)
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);

        loads.apply(loadId, 3, 3, 0, actor);
        assertThat(codeOf(() -> rawWriter.write(loadId, fileId, List.of(new RawRow(4, null, null, Map.of())))))
                .isEqualTo(WarehouseError.FND_LOAD_STATUS_TRANSITION);

        long failed = newLoad();
        loads.fail(failed, "сбой TEST", actor);
        assertThat(codeOf(() -> rawWriter.write(failed, fileId, List.of(new RawRow(1, null, null, Map.of())))))
                .isEqualTo(WarehouseError.FND_LOAD_STATUS_TRANSITION);
        assertThat(codeOf(() -> rawWriter.write(-1, fileId, List.of(new RawRow(1, null, null, Map.of())))))
                .isEqualTo(WarehouseError.FND_LOAD_STATUS_TRANSITION);

        // The facade can only write, count and read: the contract has no updates or deletes
        // Synthetic methods (a default method's lambda, coverage probes) are not part of the contract.
        assertThat(java.util.Arrays.stream(RawWriter.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic()))
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("write", "copy", "count", "read");
    }

    @Test
    @DisplayName("AC-34: запись пакетами атомарна — исключение источника на 501-й строке не оставляет ничего")
    void writeIsAtomic() {
        long loadId = newLoad();
        assertThatThrownBy(() -> rawWriter.write(loadId, null, failingAt(1000, 501)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rawWriter.read(loadId)).isEmpty();
        assertThat(loads.find(loadId).orElseThrow().status()).isEqualTo(WarehouseLoad.PENDING);

        List<RawRow> thousand = new ArrayList<>();
        for (int number = 1; number <= 1000; number++) {
            thousand.add(new RawRow(number, null, number, Map.of("n", number)));
        }
        rawWriter.write(loadId, null, thousand);
        assertThat(dwhJdbc.sql("select count(*) from raw.rows where load_id = :id")
                        .param("id", loadId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1000L);
    }

    @Test
    @DisplayName("AC-35: поколение кеша одно, чтение — только mart/cache и только связанными параметрами")
    void martReader() {
        assertThat(martReader.currentGeneration()).isEmpty();

        long generation = dwhJdbc.sql("insert into cache.generations (state, load_versions, switched_at)"
                        + " values ('current', cast(:versions as jsonb), now()) returning generation_id")
                .param("versions", "{\"" + SOURCE + "\": 1}")
                .query(Long.class)
                .single();
        MartReader.Generation current = martReader.currentGeneration().orElseThrow();
        assertThat(current.generationId()).isEqualTo(generation);
        assertThat(current.loadVersions()).contains(SOURCE);
        assertThat(current.switchedAt()).isBefore(Instant.now().plusSeconds(1));

        assertThatThrownBy(() -> dwhJdbc.sql("insert into cache.generations (state, load_versions)"
                                + " values ('current', '{}'::jsonb)")
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cache_generations_uk_current");

        dwhJdbc.sql("insert into cache.items (generation_id, item_key, load_versions, payload)"
                        + " values (:g, 'TEST-key', '{}'::jsonb, '{\"value\": 1}'::jsonb)")
                .param("g", generation)
                .update();
        List<Map<String, Object>> items = martReader.read("cache", "items", Map.of("item_key", "TEST-key"));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("item_key", "TEST-key");
        assertThat(martReader.read("cache", "items", Map.of("item_key", "нет такого")))
                .isEmpty();

        for (String forbidden : List.of("raw", "core", "public", "MART")) {
            assertThat(codeOf(() -> martReader.read(forbidden, "rows", Map.of())))
                    .isEqualTo(WarehouseError.DWH_READ_FORBIDDEN);
        }
        for (String table : List.of("items; drop table cache.items", "cache.items", "\"items\"", "items rows")) {
            assertThat(codeOf(() -> martReader.read("cache", table, Map.of())))
                    .isEqualTo(WarehouseError.DWH_READ_FORBIDDEN);
        }
        assertThat(codeOf(() -> martReader.read("cache", "items", Map.of("item_key; drop", "x"))))
                .isEqualTo(WarehouseError.DWH_READ_FORBIDDEN);
    }

    @Test
    @DisplayName("AC-36: pg-dwh недоступен — отказ за секунды и откат транзакции OLTP")
    void dwhUnavailable() {
        try (HikariDataSource closedPort = closedPortDataSource()) {
            MartReader reader = new MartReader(closedPort);
            RawWriter writer = new JdbcRawWriter(closedPort, jdbc, tx.getTransactionManager(), json);
            long loadId = newLoad();

            Instant start = Instant.now();
            assertThatThrownBy(reader::currentGeneration).isInstanceOf(WarehouseUnavailableException.class);
            assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));

            UUID packageRef = UUID.randomUUID();
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                        actors.apply(actor);
                        loads.log(packageRef, "получен", null, null, actor, "до записи TEST", null);
                        writer.write(loadId, null, List.of(new RawRow(1, null, null, Map.of("n", 1))));
                    }))
                    .isInstanceOf(WarehouseUnavailableException.class);
            // The OLTP transaction is rolled back: the log row inserted before the pg-dwh call is gone
            assertThat(jdbc.sql("select count(*) from fnd_load_log where package_ref = :p")
                            .param("p", packageRef)
                            .query(Long.class)
                            .single())
                    .isZero();
        }
    }

    // ---------- helpers ----------

    private HikariDataSource closedPortDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:postgresql://localhost:1/dwh");
        dataSource.setUsername("unused");
        dataSource.setPassword("");
        dataSource.setConnectionTimeout(2000);
        dataSource.setInitializationFailTimeout(-1);
        dataSource.addDataSourceProperty("connectTimeout", "2");
        dataSource.addDataSourceProperty("loginTimeout", "2");
        return dataSource;
    }

    private long newLoad() {
        return loads.begin(
                SOURCE, UUID.randomUUID(), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), "v1", actor);
    }

    private UUID newFile() {
        return tx.execute(status -> jdbc.sql("""
                        insert into mf_files (sha256, original_name, size_bytes, mime_type,
                                              storage_bucket, storage_key)
                        values (:sha, 'TEST.xlsx', 10, 'application/vnd.ms-excel', 'test', :key)
                        returning id
                        """)
                .param(
                        "sha",
                        UUID.randomUUID().toString().repeat(2).replace("-", "").substring(0, 64))
                .param("key", "test/" + UUID.randomUUID())
                .query(UUID.class)
                .single());
    }

    /** A row source that breaks on a given row: imitates a file parsing failure. */
    private static Iterable<RawRow> failingAt(int total, int failAt) {
        return () -> new Iterator<>() {
            private int next = 1;

            @Override
            public boolean hasNext() {
                return next <= total;
            }

            @Override
            public RawRow next() {
                if (next == failAt) {
                    throw new IllegalStateException("источник строк сломался на " + failAt + " TEST");
                }
                int number = next++;
                return new RawRow(number, null, number, Map.of("n", number));
            }
        };
    }

    private ConstraintCode codeOf(Runnable action) {
        Throwable error = catchThrowable(action::run);
        assertThat(error).isInstanceOf(ConstraintViolationException.class);
        return ((ConstraintViolationException) error).code();
    }
}
