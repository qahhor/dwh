package com.smartup24.cms.instance.common.entity.report;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityLists;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.query.QueryAggregateRepository;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryAggregates;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual PostgreSQL covers missing targets, partition and a bounded EXPLAIN of the generated source (ADR-0039). */
class EntityReportDatasetSqlTest {
    private static JdbcClient jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void setup() {
        var source = TestDatabases.migratedCopy("smc_report_dataset_sql_test");
        jdbc = JdbcClient.create(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.sql("create table dataset_root_test(id bigint primary key, name text, target_id bigint, "
                        + "qty numeric, created_by bigint)")
                .update();
        jdbc.sql("create table dataset_target_test(id bigint primary key, form_id bigint, name text, "
                        + "payload jsonb, created_by bigint, archived_at timestamptz)")
                .update();
        jdbc.sql("""
                insert into dataset_target_test values
                (1,19,'a','{"flag":true}',7,null),
                (2,20,'b','{"flag":true}',7,null),
                (3,19,'c','{"flag":false}',8,null),
                (4,19,'d','{"flag":false}',7,now()),
                (5,19,'e','{"flag":false}',7,null)
                """).update();
        jdbc.sql("""
                insert into dataset_root_test values
                (1,'a',1,2,7),(2,'b',2,3,7),(3,'c',3,5,7),
                (4,'d',4,7,7),(5,'e',99,11,7),(6,'f',5,13,7),(7,'g',1,1000,8)
                """).update();
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void partitionScopeArchiveAndMissingKeysAreNullAndCannotMultiplyRootTotals() throws Exception {
        signIn();
        var scopes = EntityScopes.withoutOrgUnits();
        var root = root();
        var target = target();
        var entities = List.of(root, target);
        var registry = new EntityRegistry(entities);
        var reads = mock(EntityReads.class);
        when(reads.list(any())).thenAnswer(call -> EntityLists.queryList(call.getArgument(0)));
        var dataset = new EntityReportDataset(
                "example.sql",
                root.code(),
                "dataset",
                "targetId",
                List.of(new EntityReportDataset.Projection("targetFlag", "flag")));
        var resolver = new EntityReportDatasets(List.of(dataset), entities, EntityGate.of(registry), reads, scopes);
        var source = resolver.resolve(root.code(), dataset.code());
        var aggregate = QueryAggregates.compile(
                source.list(),
                "[{\"field\":\"targetFlag\"}]",
                "[{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"qty\"}]",
                null);
        var rootScope = scopes.listPredicate(root, aggregate.filter(), 7);
        var params = new LinkedHashMap<>(source.params());
        params.putAll(rootScope.params());
        var scope = new QueryPlan.SqlFragment(rootScope.sql(), params);
        var data = tx.execute(status -> new QueryAggregateRepository(jdbc).run(aggregate, scope));
        assertThat(data.rows())
                .containsExactly(
                        row(List.of(false), 1L, "13"),
                        row(List.of(true), 1L, "2"),
                        row(Arrays.asList((Object) null), 4L, "26"));
        var sql = aggregate.sql(scope);
        String explain = tx.execute(status -> {
            jdbc.sql("set local statement_timeout = '5s'").update();
            return jdbc.sql("explain (analyze, buffers, format json) " + sql.sql())
                    .params(sql.params())
                    .query(String.class)
                    .single();
        });
        assertThat(explain).contains("dataset_root_test", "dataset_target_test", "Actual Rows", "Execution Time");
        Files.writeString(Path.of("target", "studio06-dataset-explain.json"), explain);
    }

    private static QueryAggregateResult.AggregateRow row(List<Object> groups, long count, String sum) {
        return new QueryAggregateResult.AggregateRow(groups, List.of(count, new BigDecimal(sum)));
    }

    private static EntityDefinition root() {
        return Entity.define("example.sqlroot", "example.sqlroot")
                .table("dataset_root_test", "r")
                .scope(EntityScope.owner("created_by"))
                .field(text("name", "name").column("name").list(sortable()))
                .field(ref("targetId", "target").column("target_id").target("example.sqltarget", "name"))
                .field(number("qty", "qty").column("qty"))
                .section("main", "main", "name", "targetId", "qty")
                .defaultSort("name", Entity.Sort.ASC)
                .build();
    }

    private static EntityDefinition target() {
        return Entity.define("example.sqltarget", "example.sqltarget")
                .table("dataset_target_test", "r")
                .partition("form_id", 19)
                .scope(EntityScope.owner("created_by"))
                .field(text("name", "name").column("name").list(sortable()))
                .field(bool("flag", "flag").jsonProperty("payload", "flag"))
                .section("main", "main", "name", "flag")
                .defaultSort("name", Entity.Sort.ASC)
                .archivable()
                .build();
    }

    private static void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                7L,
                "viewer",
                "viewer@example.test",
                1L,
                false,
                Set.of("example.sqlroot.view", "example.sqltarget.view"),
                1L,
                false,
                1L,
                null));
    }
}
