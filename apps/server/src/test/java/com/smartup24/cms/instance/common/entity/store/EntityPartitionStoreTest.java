package com.smartup24.cms.instance.common.entity.store;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.EntityLists;
import com.smartup24.cms.instance.common.entity.EntitySchemaCheck;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

class EntityPartitionStoreTest {
    private static final QueryPlan.SqlFragment ALL = new QueryPlan.SqlFragment("", Map.of());
    private static final EntityDefinition A = entity("alpha", 1);
    private static final EntityDefinition B = entity("beta", 2);
    private static HikariDataSource database;
    private static JdbcClient jdbc;
    private static EntityStoreRepository store;
    private long mine;
    private long other;

    @BeforeAll
    static void setup() {
        TestDatabases.createDatabase("partition_store");
        database = TestDatabases.pooled(
                TestDatabases.jdbcUrl("partition_store"), TestDatabases.USER, null, 2, "partition");
        jdbc = JdbcClient.create(database);
        jdbc.sql("""
                create table test_partition_records (
                    id bigint generated always as identity primary key,
                    form_id bigint not null default 1,
                    name text not null, code text, payload jsonb not null default '{}',
                    attributes jsonb not null default '{}', revision bigint not null default 1,
                    created_by bigint, modified_by bigint,
                    created_at timestamptz not null default now(), modified_at timestamptz not null default now(),
                    archived_at timestamptz, archived_by bigint,
                    unique(form_id, code))
                """).update();
        store = new EntityStoreRepository(jdbc, new QueryListRepository(jdbc), JsonMapper.shared());
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @BeforeEach
    void seed() {
        jdbc.sql("delete from test_partition_records").update();
        mine = insert(1, "Alpha");
        other = insert(2, "Beta");
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L,
                "partition",
                "partition@test.local",
                1L,
                false,
                Set.of("test.alpha.view", "test.beta.view"),
                1L,
                false,
                0,
                null));
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void readsAndPagesNeverCrossThePartition() {
        assertThat(store.find(A, EntityLists.queryList(A), other, ALL, false)).isEmpty();
        assertThat(store.find(A, EntityLists.queryList(A), mine, ALL, true)).isPresent();
        var plan = QueryCompiler.compile(EntityLists.queryList(A), null, null, 20, null, null);
        assertThat(store.page(A, plan, ALL).items())
                .extracting(row -> row.get("name"))
                .containsExactly("Alpha");
        assertThat(store.visibleRows(A, Set.of(mine, other), ALL)).containsOnlyKeys(mine);
    }

    @Test
    void everyMutationRejectsAnIdOfAnotherPartition() {
        EntityWrite write = EntityWrite.of(A, Map.of("name", "Changed"), store.json(A));
        assertThat(store.update(A, other, 1, write, null, 1)).isEmpty();
        assertThat(store.archive(A, other, true, 1)).isEmpty();
        assertThat(store.delete(A, other)).isFalse();
        assertThat(store.find(B, EntityLists.queryList(B), other, ALL, false).orElseThrow())
                .containsEntry("name", "Beta")
                .containsEntry("revision", 1L);
    }

    @Test
    void insertsBindThePartitionAndPersistTypedJsonValues() {
        EntityWrite write = EntityWrite.of(B, values(), store.json(B));
        long id = store.insert(B, write, null, 1);
        assertThat(jdbc.sql("select form_id from test_partition_records where id=:id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        Map<String, Object> record =
                store.find(B, EntityLists.queryList(B), id, ALL, false).orElseThrow();
        assertThat(record)
                .containsEntry("amount", new BigDecimal("12.50"))
                .containsEntry("enabled", true)
                .containsEntry("day", "2026-10-15")
                .containsEntry("moment", "2026-10-15T07:30:00Z")
                .containsEntry("at", "12:30")
                .containsEntry("note", "Body");
    }

    @Test
    void jsonPatchesPreserveSiblingsAndExplicitNullClearsOnlyItsKey() {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("note", null);
        patch.put("amount", "22.75");
        assertThat(store.update(A, mine, 1, EntityWrite.of(A, patch, store.json(A)), null, 1))
                .contains(2L);
        Map<String, Object> record =
                store.find(A, EntityLists.queryList(A), mine, ALL, false).orElseThrow();
        assertThat(record)
                .containsEntry("amount", new BigDecimal("22.75"))
                .containsEntry("enabled", true)
                .doesNotContainKey("note");
        assertThat(jdbc.sql("select payload->>'untouched' from test_partition_records where id=:id")
                        .param("id", mine)
                        .query(String.class)
                        .single())
                .isEqualTo("Kept");
    }

    @Test
    void schemaChecksThePhysicalPartitionAndPayload() {
        assertThat(EntitySchemaCheck.of(jdbc).problems(List.of(A))).isEmpty();
        var columns = new LinkedHashMap<>(EntitySchemaCheck.of(jdbc).columns("test_partition_records"));
        columns.put("form_id", new EntitySchemaCheck.Column("text", true, true));
        columns.put("payload", new EntitySchemaCheck.Column("text", false, true));
        assertThat(new EntitySchemaCheck(Map.of("test_partition_records", columns), Map.of()).problems(List.of(A)))
                .anyMatch(problem -> problem.contains("form_id"))
                .anyMatch(problem -> problem.contains("payload"));
    }

    private static long insert(long partition, String name) {
        return jdbc.sql("""
                insert into test_partition_records(form_id, name, payload) values (:partition, :name,
                '{"amount":12.50,"enabled":true,"note":"Body","untouched":"Kept"}'::jsonb) returning id
                """)
                .param("partition", partition)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private static Map<String, Object> values() {
        return Map.of(
                "name",
                "Created",
                "amount",
                "12.50",
                "enabled",
                true,
                "note",
                "Body",
                "day",
                "2026-10-15",
                "moment",
                "2026-10-15T12:30:00+05:00",
                "at",
                "12:30");
    }

    static EntityDefinition entity(String code, long partition) {
        return Entity.define("test." + code, "test." + code)
                .table("test_partition_records", "p")
                .partition("form_id", partition)
                .scope(EntityScope.all())
                .field(text("name", "test.name")
                        .column("name")
                        .required()
                        .list(sortable().searchable()))
                .field(text("code", "test.code").column("code"))
                .field(number("amount", "test.amount")
                        .jsonProperty("payload", "amount")
                        .required()
                        .scale(2))
                .field(textarea("note", "test.note").jsonProperty("payload", "note"))
                .field(bool("enabled", "test.enabled").jsonProperty("payload", "enabled"))
                .field(date("day", "test.day").jsonProperty("payload", "day"))
                .field(instant("moment", "test.moment").jsonProperty("payload", "moment"))
                .field(time("at", "test.at").jsonProperty("payload", "at"))
                .section("main", "test.main", "name", "code", "amount", "note", "enabled", "day", "moment", "at")
                .actions("create", "update", "delete")
                .archivable()
                .defaultSort("name", Entity.Sort.ASC)
                .build();
    }
}
