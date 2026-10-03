package com.smartup24.cms.instance.common.entity.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityEnumResolver;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.EntityFieldValues;
import com.smartup24.cms.instance.common.entity.EntityFiles;
import com.smartup24.cms.instance.common.entity.EntityLists;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.FieldTypesFixture;
import com.smartup24.cms.instance.common.entity.store.EntityCollectionStore;
import com.smartup24.cms.instance.common.entity.store.EntityStoreRepository;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import com.smartup24.cms.instance.mf.repository.MfRecordFileRepository;
import com.smartup24.cms.instance.mf.service.MfAttachments;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.spi.storage.StorageProvider;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runtime writes every field type (ADR-0032, 4.1 and 6.3, step 10; deviation T4 of ADR-0032, 4.9 closed): a create
 * and an update of the test entity with a field of every type, on a database of its own — the columns of money, the
 * rows of the link table in order, the attachment of a file field, JSON, the custom attributes, a number from a
 * sequence and a default — read back by the list's projection; a delete takes the link rows and the attachments.
 */
class EntityRuntimeFieldTypesIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.shared();

    private static DataSource dataSource;
    private static JdbcClient jdbc;
    private static EntityWrites writes;
    private static EntityDefinition entity;
    private static long ann;
    private static long kg;
    private static long pc;
    private static UUID annPhoto;
    private static UUID annPdf;

    @BeforeAll
    static void createTables() {
        dataSource = TestDatabases.migratedCopy("rt_field_types");
        jdbc = JdbcClient.create(dataSource);
        for (String statement : FieldTypesFixture.DDL.split(";")) {
            if (!statement.isBlank()) jdbc.sql(statement).update();
        }
        ann = id(
                "insert into md_users (name, login, email) values ('Ann', 'rt_ann', 'rt_ann@test.local') returning id");
        kg = id("insert into test_units (code, name, sort_order) values ('kg', 'Kilogram', 1) returning id");
        pc = id("insert into test_units (code, name, sort_order) values ('pc', 'Piece', 2) returning id");
        annPhoto = file("shelf.png", "image/png");
        annPdf = file("act.pdf", "application/pdf");

        List<EntityDefinition> entities = List.of(FieldTypesFixture.REFERENCE, FieldTypesFixture.DEFINITION);
        EntityEnums enums = new EntityEnums(entities, jdbc, (org.springframework.cache.CacheManager) null);
        QueryListRegistry lists = new QueryListRegistry(
                List.of(),
                List.of(new EntityLists(entities)),
                List.of(),
                List.of(new EntityEnumResolver(entities, enums)));
        QueryListRepository listRepository = new QueryListRepository(jdbc);
        MfAttachments attachments = new MfAttachments(
                new MfRecordFileRepository(jdbc),
                new MfFileRepository(jdbc, listRepository),
                mock(StorageProvider.class),
                mock(AuditLogService.class));
        EntityRegistry registry = new EntityRegistry(entities);
        EntityStoreRepository store = new EntityStoreRepository(jdbc, listRepository, JSON);
        EntityScopes scopes = EntityScopes.withoutOrgUnits();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("files", attachments);
        EntityLines lines = new EntityLines(new EntityCollectionStore(jdbc, JSON));
        EntityReads reads = new EntityReads(lists, scopes, store, lines);
        writes = new EntityWrites(
                reads,
                store,
                new EntityFieldValues(enums, attachments, jdbc, java.time.Clock.systemUTC()),
                new EntitySaveChecks(
                        registry,
                        store,
                        scopes,
                        beans.getBeanProvider(com.smartup24.cms.instance.common.entity.EntityAttributes.class)),
                new EntityChanges(
                        registry,
                        store,
                        beans.getBeanProvider(EntityAuditLog.class),
                        beans.getBeanProvider(EntityFiles.class)),
                new EntityEvents(event -> {}, registry),
                lines);
        entity = registry.find(FieldTypesFixture.CODE).orElseThrow();
    }

    @AfterAll
    static void close() {
        ((HikariDataSource) dataSource).close();
    }

    @BeforeEach
    void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                ann, "rt_ann", "rt_ann@test.local", 1L, false, Set.of("test_field_types.view"), 1L, false, 0, null));
    }

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("4.1: a create writes money, the link rows in order, a file, JSON, attributes, a sequence number")
    void aCreateWritesEveryType() {
        Map<String, Object> record = writes.create(entity, json("""
                {"title": "Shelf", "qty": "12.5", "startsAt": "2026-10-01T09:30:00Z", "callTime": "09:30",
                 "email": "Ann@Example.com", "phone": "+998 (90) 123-45-67", "site": "https://example.com/a",
                 "total": {"amount": "1250.50", "currency": "UZS"}, "unit": "kg", "tagIds": [%d, %d],
                 "attachment": "%s", "photo": "%s", "extra": {"a": [1, 2]}, "code": "C-1",
                 "attributes": {"region": "north", "level": "3"}}
                """.formatted(pc, kg, annPdf, annPhoto)));

        long id = ((Number) record.get("id")).longValue();
        assertThat(record)
                .containsEntry("title", "Shelf")
                .containsEntry("email", "ann@example.com")
                .containsEntry("phone", "+998901234567")
                .containsEntry("unit", "kg")
                .containsEntry("tagIds", List.of(pc, kg))
                .containsEntry("total", Map.of("amount", "1250.50", "currency", "UZS"))
                .containsEntry("kind", "retail")
                .containsEntry("active", true)
                .containsEntry("number", "T-0001")
                .containsEntry("region", "north")
                .containsEntry("code", "C-1");
        assertThat(record.get("extra")).isEqualTo(Map.of("a", List.of(1, 2)));
        assertThat(((Map<?, ?>) record.get("attachment")).get("id")).isEqualTo(annPdf.toString());
        assertThat(attached(id)).containsExactlyInAnyOrder("attachment", "photo");

        Map<String, Object> changed = writes.update(entity, id, 1, json("""
                {"total": {"amount": "10", "currency": "USD"}, "tagIds": [%d], "photo": null, "code": "C-1"}
                """.formatted(kg)));
        assertThat(changed)
                .containsEntry("revision", 2L)
                .containsEntry("tagIds", List.of(kg))
                .containsEntry("total", Map.of("amount", "10.00", "currency", "USD"))
                .doesNotContainKey("photo");
        assertThat(attached(id)).containsExactly("attachment");
        assertThatThrownBy(() -> writes.update(entity, id, 2, json("{\"code\": \"C-2\"}")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(item -> item.field() + ":" + item.code())
                                .containsExactly("code:readonly"));

        writes.delete(entity, id, null);
        assertThat(jdbc.sql("select count(*) from test_field_type_tags where record_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(attached(id)).isEmpty();
    }

    private static List<String> attached(long id) {
        return jdbc.sql("select field_key from mf_record_files where entity = :entity and record_id = :id")
                .param("entity", FieldTypesFixture.CODE)
                .param("id", id)
                .query(String.class)
                .list();
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static UUID file(String name, String type) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into mf_files (id, sha256, original_name, size_bytes, mime_type, storage_bucket,
                            storage_key, created_by)
                        values (:id, :sha, :name, 10, :type, 'b', :key, :owner)""")
                .param("id", id)
                .param("sha", id.toString().replace("-", ""))
                .param("name", name)
                .param("type", type)
                .param("key", "k/" + id)
                .param("owner", ann)
                .update();
        return id;
    }

    private static long id(String sql) {
        Long id = jdbc.sql(sql).query(Long.class).single();
        return id == null ? 0 : id;
    }
}
