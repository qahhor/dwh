package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import com.smartup24.cms.instance.mf.repository.MfRecordFileRepository;
import com.smartup24.cms.instance.mf.service.MfAttachments;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.spi.storage.StorageProvider;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan 10/10, item 5.2 (ADR-0032, 4.1–4.7) on a database of its own: the test entity with a field of every type is
 * read back by its list — money with its currency, several references from the link table, a file from the published
 * view of the files module, JSON, a computed value, attributes cast to their types — filtered by every kind of field,
 * its enumeration's items read from the reference entity, and a save prepared with defaults, read-only fields,
 * conditional visibility and the files a person may attach.
 */
class FieldTypesIntegrationTest {

    private static final String CODE = FieldTypesFixture.CODE;

    private static DataSource dataSource;
    private static JdbcClient jdbc;
    private static QueryListRegistry registry;
    private static QueryListRepository repository;
    private static EntityFieldValues values;
    private static MfAttachments attachments;
    private static long ann;
    private static long bob;
    private static long kg;
    private static long pc;
    private static UUID annPhoto;
    private static UUID annPdf;
    private static UUID annLarge;
    private static UUID bobPdf;
    private static EntityEnums enums;
    private static long alpha;
    private static long beta;

    @BeforeAll
    static void createTables() {
        dataSource = TestDatabases.migratedCopy("field_types");
        jdbc = JdbcClient.create(dataSource);
        for (String statement : FieldTypesFixture.DDL.split(";")) {
            if (!statement.isBlank()) jdbc.sql(statement).update();
        }
        ann = id("insert into md_users (name, login, email) values ('Ann', 'ft_ann', 'ann@test.local') returning id");
        bob = id("insert into md_users (name, login, email) values ('Bob', 'ft_bob', 'bob@test.local') returning id");
        kg = id("insert into test_units (code, name, sort_order) values ('kg', 'Kilogram', 1) returning id");
        pc = id("insert into test_units (code, name, sort_order) values ('pc', 'Piece', 2) returning id");
        jdbc.sql("insert into test_units (code, name, sort_order, archived_at) values ('lb', 'Pound', 3, now())")
                .update();
        annPhoto = file(ann, "shelf.png", "image/png", 10);
        annPdf = file(ann, "act.pdf", "application/pdf", 20);
        annLarge = file(ann, "big.pdf", "application/pdf", 2_000_000);
        bobPdf = file(bob, "bob.pdf", "application/pdf", 30);
        alpha = id("""
                insert into test_field_types (title, qty, email, phone, site, total_amount, total_currency, unit,
                    attachment_id, photo_id, extra, kind, attributes)
                values ('Alpha', 2, 'ann@example.com', '+998901234567', 'https://a.example', 100, 'UZS', 'kg',
                    '%s', '%s', '{"a": 1}', 'retail', '{"region": "north", "level": "3"}')
                returning id""".formatted(annPdf, annPhoto));
        beta = id("""
                insert into test_field_types (title, qty, total_amount, total_currency, unit, kind, owner_id, attributes)
                values ('Beta', 5, 10, 'USD', 'pc', 'wholesale', %d, '{"level": "x"}')
                returning id""".formatted(ann));
        jdbc.sql("insert into test_field_type_tags (record_id, unit_id, position) values (:r, :pc, 2), (:r, :kg, 1)")
                .param("r", alpha)
                .param("pc", pc)
                .param("kg", kg)
                .update();

        List<EntityDefinition> entities = List.of(FieldTypesFixture.REFERENCE, FieldTypesFixture.DEFINITION);
        enums = new EntityEnums(entities, jdbc, (org.springframework.cache.CacheManager) null);
        registry = new QueryListRegistry(
                List.of(),
                List.of(new EntityLists(entities)),
                List.of(),
                List.of(new EntityEnumResolver(entities, enums)));
        repository = new QueryListRepository(jdbc);
        attachments = new MfAttachments(
                new MfRecordFileRepository(jdbc),
                new MfFileRepository(jdbc, repository),
                mock(StorageProvider.class),
                mock(AuditLogService.class));
        values = new EntityFieldValues(enums, attachments, jdbc, java.time.Clock.systemUTC());
    }

    @AfterAll
    static void close() {
        ((HikariDataSource) dataSource).close();
    }

    @Test
    void theListReadsEveryTypeBack() {
        Map<String, Object> row = page(null, null).items().stream()
                .filter(item -> item.get("id").equals(alpha))
                .findFirst()
                .orElseThrow();

        assertThat(row)
                .containsEntry("title", "Alpha")
                .containsEntry("unit", "kg")
                .containsEntry("kind", "retail");
        assertThat(row.get("total")).isEqualTo(Map.of("amount", "100.00", "currency", "UZS"));
        assertThat(row.get("tagIds")).isEqualTo(List.of(kg, pc));
        assertThat(row.get("photo"))
                .isEqualTo(
                        Map.of("id", annPhoto.toString(), "name", "shelf.png", "size", 10, "contentType", "image/png"));
        assertThat(row.get("extra")).isEqualTo(Map.of("a", 1));
        assertThat(row.get("doubled").toString()).startsWith("4");
        assertThat(row).containsEntry("region", "north");
        assertThat(row.get("level").toString()).startsWith("3");
        assertThat(row).doesNotContainKey("totalCurrency");

        Map<String, Object> other = page(null, null).items().stream()
                .filter(item -> item.get("id").equals(beta))
                .findFirst()
                .orElseThrow();
        assertThat(other.get("tagIds")).isEqualTo(List.of());
        assertThat(other).doesNotContainKeys("photo", "extra", "level");
        assertThat(other).containsEntry("ownerId", ann);
    }

    @Test
    void everyKindOfFieldFilters() {
        assertThat(titles("[{\"field\":\"title\",\"op\":\"contains\",\"value\":\"alp\"}]"))
                .containsExactly("Alpha");
        assertThat(titles("[{\"field\":\"email\",\"op\":\"eq\",\"value\":\"ann@example.com\"}]"))
                .containsExactly("Alpha");
        assertThat(titles("[{\"field\":\"site\",\"op\":\"starts_with\",\"value\":\"https://a\"}]"))
                .containsExactly("Alpha");
        assertThat(titles("[{\"field\":\"qty\",\"op\":\"gt\",\"value\":3}]")).containsExactly("Beta");
        assertThat(titles("[{\"field\":\"total\",\"op\":\"gte\",\"value\":50}]"))
                .containsExactly("Alpha");
        assertThat(titles("[{\"field\":\"totalCurrency\",\"op\":\"eq\",\"value\":\"USD\"}]"))
                .containsExactly("Beta");
        assertThat(titles("[{\"field\":\"unit\",\"op\":\"in\",\"value\":[\"pc\"]}]"))
                .containsExactly("Beta");
        assertThat(titles("[{\"field\":\"tagIds\",\"op\":\"in\",\"value\":[" + pc + "]}]"))
                .containsExactly("Alpha");
        assertThat(titles("[{\"field\":\"tagIds\",\"op\":\"empty\"}]")).containsExactly("Beta");
        assertThat(titles("[{\"field\":\"photo\",\"op\":\"not_empty\"}]")).containsExactly("Alpha");
        assertThat(titles("[{\"field\":\"attachment\",\"op\":\"empty\"}]")).containsExactly("Beta");
        assertThat(titles("[{\"field\":\"extra\",\"op\":\"not_empty\"}]")).containsExactly("Alpha");
        assertThat(titles("[{\"field\":\"level\",\"op\":\"gt\",\"value\":2}]")).containsExactly("Alpha");
        assertThat(titles("[{\"field\":\"ownerId\",\"op\":\"eq\",\"value\":" + ann + "}]"))
                .containsExactly("Beta");
        assertThat(titles("[{\"field\":\"doubled\",\"op\":\"lt\",\"value\":5}]"))
                .containsExactly("Alpha");
        assertThatThrownBy(() -> titles("[{\"field\":\"unit\",\"op\":\"eq\",\"value\":\"zz\"}]"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> titles("[{\"field\":\"extra\",\"op\":\"eq\",\"value\":\"x\"}]"))
                .isInstanceOf(ApiException.class);

        List<String> byTotal = page(null, "total").items().stream()
                .map(item -> (String) item.get("title"))
                .toList();
        assertThat(byTotal).containsExactly("Beta", "Alpha");
    }

    @Test
    void anEnumerationTakesTheItemsOfItsReferenceAtRequestTime() {
        QueryField unit = registry.get(CODE).field("unit").orElseThrow();
        // The list filters old records by an archived item too (ADR-0032, 5.4); a new value is offered only active
        // ones.
        assertThat(unit.enumValues()).containsExactly("kg", "pc", "lb");
        assertThat(unit.enumLabels()).containsEntry("kg", "Kilogram").containsEntry("pc", "Piece");
        assertThat(unit.format()).isEqualTo("enum");
        EntityEnums.Items items = enums.all(FieldTypesFixture.UNITS);
        assertThat(items.archived()).containsExactly("lb");
        assertThat(items.offered()).containsOnlyKeys("kg", "pc");
    }

    @Test
    void aSaveTakesDefaultsAndKeepsValuesInTheirForm() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Gamma");
        body.put("email", "Ann@Example.COM");
        body.put("phone", "+998 (90) 123-45-67");
        body.put("ownerId", ann);
        body.put("photo", annPhoto.toString());

        Map<String, Object> prepared = values.prepare(FieldTypesFixture.DEFINITION, body, null, null, ann);

        assertThat(prepared)
                .containsEntry("email", "ann@example.com")
                .containsEntry("phone", "+998901234567")
                .containsEntry("kind", "retail")
                .containsEntry("active", true)
                .containsEntry("due", LocalDate.now(ZoneOffset.UTC).toString());
        assertThat((String) prepared.get("number")).matches("^T-\\d{4}$");
        assertThat(prepared).as("hidden while the kind is retail").containsEntry("ownerId", null);
    }

    @Test
    void aSaveRefusesWhatItMayNotWrite() {
        assertThat(codes(Map.of("title", "x", "number", "T-9999", "doubled", 3), null))
                .containsEntry("number", "readonly")
                .containsEntry("doubled", "readonly");
        assertThat(codes(Map.of("title", "x", "kind", "wholesale"), null)).containsEntry("ownerId", "required");
        assertThat(codes(Map.of("title", "x", "unit", "zz"), null)).containsEntry("unit", "invalid");
        assertThat(codes(Map.of("title", "x", "unit", "lb"), null)).containsEntry("unit", "archived");
        assertThat(codes(Map.of("title", "x", "attachment", bobPdf.toString()), null))
                .containsEntry("attachment", "not_found");
        assertThat(codes(Map.of("title", "x", "attachment", annLarge.toString()), null))
                .containsEntry("attachment", "too_large");
        assertThat(codes(Map.of("title", "x", "photo", annPdf.toString()), null))
                .containsEntry("photo", "invalid");

        Map<String, Object> current = Map.of("title", "Alpha", "kind", "retail", "code", "A-1", "number", "T-0001");
        assertThat(codes(Map.of("code", "A-2"), current)).containsEntry("code", "readonly");
        Map<String, Object> same = values.prepare(
                FieldTypesFixture.DEFINITION, Map.of("code", "A-1", "title", "Alpha 2"), current, alpha, ann);
        assertThat(same).containsEntry("title", "Alpha 2").doesNotContainKey("code");
    }

    @Test
    void aFileIsAttachedToItsRecordAndReadThroughIt() {
        attachments.attach(CODE, beta, "attachment", bobPdf);

        assertThat(attachments.attachable(bobPdf, CODE, beta, ann))
                .as("attached to this record")
                .isPresent();
        assertThat(attachments.attachable(bobPdf, CODE, alpha, ann))
                .as("another record")
                .isEmpty();
        assertThat(attachments.attachable(annPdf, CODE, null, ann))
                .as("the person's own upload")
                .isPresent();
        assertThat(attachments.attached(CODE, beta, bobPdf).orElseThrow().name())
                .isEqualTo("bob.pdf");
        assertThatThrownBy(() -> jdbc.sql("delete from mf_files where id = :id")
                        .param("id", bobPdf)
                        .update())
                .as("an attached file is not deleted")
                .isInstanceOf(DataIntegrityViolationException.class);

        attachments.attach(CODE, beta, "attachment", null);
        assertThat(attachments.attached(CODE, beta, bobPdf)).isEmpty();
        attachments.attach(CODE, beta, "photo", annPhoto);
        attachments.detachAll(CODE, beta);
        assertThat(attachments.attached(CODE, beta, annPhoto)).isEmpty();
    }

    private static Map<String, String> codes(Map<String, Object> body, Map<String, Object> current) {
        try {
            values.prepare(FieldTypesFixture.DEFINITION, body, current, current == null ? null : alpha, ann);
            return Map.of();
        } catch (ApiException e) {
            Map<String, String> codes = new HashMap<>();
            Objects.requireNonNull(e.getFieldErrors()).forEach(error -> codes.put(error.field(), error.code()));
            return codes;
        }
    }

    private static List<String> titles(String filter) {
        return page(filter, null).items().stream()
                .map(item -> (String) item.get("title"))
                .sorted()
                .toList();
    }

    private static KeysetPage<Map<String, Object>> page(String filter, String sort) {
        QueryList list = registry.get(CODE);
        EntityModel model = Objects.requireNonNull(FieldTypesFixture.DEFINITION.model());
        return repository.page(
                QueryCompiler.compile(list, filter, sort, 50, null), new EntityRowMapper(model, JsonMapper.shared()));
    }

    private static UUID file(long owner, String name, String type, long size) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into mf_files (id, sha256, original_name, size_bytes, mime_type, storage_bucket,
                            storage_key, created_by)
                        values (:id, :sha, :name, :size, :type, 'b', :key, :owner)""")
                .param("id", id)
                .param("sha", id.toString().replace("-", ""))
                .param("name", name)
                .param("size", size)
                .param("type", type)
                .param("key", "k/" + id)
                .param("owner", owner)
                .update();
        return id;
    }

    private static long id(String sql) {
        return Objects.requireNonNull(jdbc.sql(sql).query(Long.class).single());
    }
}
