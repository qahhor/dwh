package com.smartup24.cms.instance.common.entity.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityAttributes;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.EntityFieldValues;
import com.smartup24.cms.instance.common.entity.EntityFiles;
import com.smartup24.cms.instance.common.entity.EntityLists;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.runtime.EntityAuditLog;
import com.smartup24.cms.instance.common.entity.runtime.EntityChanges;
import com.smartup24.cms.instance.common.entity.runtime.EntityEvents;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.entity.runtime.EntityLines;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.entity.runtime.EntitySaveChecks;
import com.smartup24.cms.instance.common.entity.runtime.EntityWrites;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class EntityPartitionRuntimeTest {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static HikariDataSource database;
    private static EntityRuntime runtime;

    @BeforeAll
    static void setup() {
        TestDatabases.createDatabase("partition_runtime");
        database = TestDatabases.pooled(
                TestDatabases.jdbcUrl("partition_runtime"), TestDatabases.USER, null, 2, "partitionrt");
        JdbcClient jdbc = JdbcClient.create(database);
        jdbc.sql("""
                create table test_partition_records (
                    id bigint generated always as identity primary key, form_id bigint not null,
                    name text not null, code text, payload jsonb not null default '{}', attributes jsonb not null default '{}',
                    revision bigint not null default 1, created_by bigint, modified_by bigint,
                    created_at timestamptz not null default now(), modified_at timestamptz not null default now(),
                    archived_at timestamptz, archived_by bigint, unique(form_id, code))
                """).update();
        List<EntityDefinition> entities =
                List.of(EntityPartitionStoreTest.entity("alpha", 1), EntityPartitionStoreTest.entity("beta", 2));
        runtime = runtime(entities, jdbc);
    }

    private static EntityRuntime runtime(List<EntityDefinition> entities, JdbcClient jdbc) {
        EntityRegistry registry = new EntityRegistry(entities);
        var lists = new QueryListRegistry(List.of(), List.of(new EntityLists(entities)), List.of());
        EntityStoreRepository store = new EntityStoreRepository(jdbc, new QueryListRepository(jdbc), JSON);
        EntityScopes scopes = EntityScopes.withoutOrgUnits();
        EntityLines lines = new EntityLines(new EntityCollectionStore(jdbc, JSON));
        EntityReads reads = new EntityReads(lists, scopes, store, lines);
        var beans = new StaticListableBeanFactory();
        EntityWrites writes = new EntityWrites(
                reads,
                store,
                new EntityFieldValues(
                        new EntityEnums(List.of(), jdbc, (org.springframework.cache.CacheManager) null),
                        null,
                        jdbc,
                        Clock.systemUTC()),
                new EntitySaveChecks(registry, store, scopes, beans.getBeanProvider(EntityAttributes.class)),
                new EntityChanges(
                        registry,
                        store,
                        beans.getBeanProvider(EntityAuditLog.class),
                        beans.getBeanProvider(EntityFiles.class)),
                new EntityEvents(event -> {}, registry),
                lines);
        return new EntityRuntime(
                EntityGate.of(registry), registry, reads, writes, new DataSourceTransactionManager(database));
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @BeforeEach
    void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L,
                "partition",
                "partition@test.local",
                1L,
                false,
                Set.of(
                        "test.alpha.view",
                        "test.alpha.create",
                        "test.alpha.update",
                        "test.alpha.delete",
                        "test.beta.view",
                        "test.beta.create",
                        "test.beta.update",
                        "test.beta.delete"),
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
    void runtimeValidatesPersistsAndPatchesOrdinaryJsonFields() {
        var created = runtime.create("test.alpha", JSON.readTree("""
                {"name":" Runtime ","amount":"12.50","enabled":true,"note":"Body",
                 "day":"2026-10-15","moment":"2026-10-15T12:30:00+05:00","at":"12:30"}
                """));
        assertThat(created.properties())
                .containsEntry("amount", new BigDecimal("12.50"))
                .containsEntry("moment", "2026-10-15T07:30:00Z")
                .containsEntry("note", "Body");
        var updated = runtime.update(
                "test.alpha", created.id(), "\"1\"", JSON.readTree("{\"amount\":\"21.75\",\"note\":null}"));
        assertThat(updated.properties())
                .containsEntry("amount", new BigDecimal("21.75"))
                .containsEntry("enabled", true)
                .doesNotContainKey("note");
        assertThatThrownBy(
                        () -> runtime.update("test.alpha", created.id(), "\"1\"", JSON.readTree("{\"name\":\"Lost\"}")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void runtimeRejectsMissingRequiredInvalidScaleAndMassAssignment() {
        for (String body : List.of(
                "{\"name\":\"Missing\"}",
                "{\"name\":\"Scale\",\"amount\":\"1.234\"}",
                "{\"name\":\"Partition\",\"amount\":1,\"form_id\":2}",
                "{\"name\":\"Container\",\"amount\":1,\"payload\":{\"amount\":100}}",
                "{\"name\":\"Attributes\",\"amount\":1,\"attributes\":{\"amount\":100}}")) {
            assertThatThrownBy(() -> runtime.create("test.alpha", JSON.readTree(body)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void runtimeDoesNotReadChangeArchiveOrDeleteAnotherFormsRecord() {
        var record = runtime.create("test.beta", JSON.readTree("{\"name\":\"Other\",\"amount\":1}"));
        assertThatThrownBy(() -> runtime.get("test.alpha", record.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() ->
                        runtime.update("test.alpha", record.id(), "\"1\"", JSON.readTree("{\"name\":\"Changed\"}")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> runtime.archive("test.alpha", record.id(), "\"1\"", true))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> runtime.delete("test.alpha", record.id(), "\"1\""))
                .isInstanceOf(ApiException.class);
        assertThat(runtime.get("test.beta", record.id()).properties())
                .containsEntry("name", "Other")
                .containsEntry("revision", 1L);
    }
}
