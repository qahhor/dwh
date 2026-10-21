package com.smartup24.cms.instance.warehouse.mart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.warehouse.WarehousePref;
import com.smartup24.cms.instance.warehouse.api.MartRow;
import com.smartup24.cms.instance.warehouse.api.WarehouseMarts;
import com.smartup24.cms.instance.warehouse.api.WarehouseUnavailableException;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Publishing and reading report marts of pg-dwh (ADR-0048). */
class JdbcWarehouseMartsTest extends EmbeddedPostgresTest {

    private static final String SOURCE = "src_test_marts";
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);
    private static final QueryField SHEET =
            QueryField.of("sheet", "s", QueryFieldType.TEXT, "m.sheet").asSortable();
    private static final QueryList LIST =
            new QueryList("test.marts", "test.form", "view", "m.id", "mart.rows m", "m.id", List.of(SHEET), "sheet");

    @Autowired
    private WarehouseMarts marts;

    @Autowired
    @Qualifier(WarehousePref.QUALIFIER)
    private JdbcClient dwhJdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MartReader reader;

    @BeforeEach
    void clean() {
        dwhJdbc.sql("delete from mart.rows where source_code like 'src_test_%'").update();
    }

    private static MartRow row(long no) {
        return new MartRow(no, "Sheet1", Map.of("n", no));
    }

    private long count() {
        return dwhJdbc.sql("select count(*) from mart.rows where source_code = :s")
                .param("s", SOURCE)
                .query(Long.class)
                .single();
    }

    private static QueryAggregate countBySheet() {
        QueryPlan plan = QueryCompiler.compile(LIST, null, null, null, null);
        return new QueryAggregate(
                LIST,
                List.of(new QueryAggregate.Group(SHEET, null, false)),
                List.of(new QueryAggregate.Measure(QueryAggregate.Op.COUNT, null)),
                plan);
    }

    @Test
    @DisplayName("ADR-0048: a load is published, replaced, and the loads outside the active set are dropped")
    void publishAndPrune() {
        marts.publishLoad(SOURCE, 9001L, FROM, TO, List.of(row(1), row(2)), List.of());
        assertThat(count()).isEqualTo(2);
        assertThat(marts.materializedLoadIds(SOURCE, List.of())).isEmpty();
        assertThat(marts.materializedLoadIds(SOURCE, List.of(9001L, 9002L))).containsExactly(9001L);

        marts.publishLoad(SOURCE, 9002L, FROM, TO, List.of(row(1)), List.of(9001L, 9002L));
        assertThat(count()).isEqualTo(3);

        // an empty load keeps only the active loads; a load that is not active is dropped
        marts.publishLoad(SOURCE, 9003L, FROM, TO, List.of(), List.of(9003L));
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("ADR-0048: the aggregate sees only the active loads; no active load gives an empty answer")
    void aggregateScopedToActiveLoads() {
        marts.publishLoad(SOURCE, 9011L, FROM, TO, List.of(row(1), row(2)), List.of(9011L));
        QueryPlan.SqlFragment extra = new QueryPlan.SqlFragment("", Map.of());

        QueryAggregateResult active = marts.aggregate(SOURCE, List.of(9011L), countBySheet(), extra);
        assertThat(active.rows()).hasSize(1);

        QueryAggregateResult none = marts.aggregate(SOURCE, List.of(), countBySheet(), extra);
        assertThat(none.rows()).isEmpty();
    }

    @Test
    @DisplayName("ADR-0048: pg-dwh unavailable is a warehouse failure on every operation")
    void unavailable() {
        try (HikariDataSource closed = new HikariDataSource()) {
            closed.setJdbcUrl("jdbc:postgresql://localhost:1/dwh");
            closed.setUsername("unused");
            closed.setPassword("");
            closed.setConnectionTimeout(1000);
            closed.setInitializationFailTimeout(-1);
            closed.addDataSourceProperty("connectTimeout", "1");
            WarehouseMarts broken = new JdbcWarehouseMarts(closed, json);
            QueryPlan.SqlFragment extra = new QueryPlan.SqlFragment("", Map.of());

            assertThatThrownBy(() -> broken.publishLoad(SOURCE, 1L, FROM, TO, List.of(row(1)), List.of()))
                    .isInstanceOf(WarehouseUnavailableException.class);
            assertThatThrownBy(() -> broken.materializedLoadIds(SOURCE, List.of(1L)))
                    .isInstanceOf(WarehouseUnavailableException.class);
            assertThatThrownBy(() -> broken.aggregate(SOURCE, List.of(), countBySheet(), extra))
                    .isInstanceOf(WarehouseUnavailableException.class);
        }
    }

    @Test
    @DisplayName("ADR-0048: a mart is read without filters; a generation without a switch moment has no instant")
    void readerWithoutFilters() {
        marts.publishLoad(SOURCE, 9021L, FROM, TO, List.of(row(1)), List.of(9021L));
        assertThat(reader.read("mart", "rows", null))
                .extracting(r -> r.get("source_code"))
                .contains(SOURCE);
        assertThat(reader.read("mart", "rows", Map.of())).isNotEmpty();

        dwhJdbc.sql("delete from cache.items").update();
        dwhJdbc.sql("delete from cache.generations").update();
        dwhJdbc.sql("insert into cache.generations (state, load_versions) values ('current', '{}'::jsonb)")
                .update();
        assertThat(reader.currentGeneration().orElseThrow().switchedAt()).isNull();
        dwhJdbc.sql("delete from cache.generations").update();
    }
}
