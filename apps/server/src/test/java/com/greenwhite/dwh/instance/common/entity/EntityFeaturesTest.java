package com.greenwhite.dwh.instance.common.entity;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.bulk.BulkRunner.BulkRequest;
import com.greenwhite.dwh.instance.common.bulk.BulkRunner.BulkResult;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.EntityAction;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.FormSection;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.history.RecordHistorySource;
import com.greenwhite.dwh.instance.common.query.QueryListExporter;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.ms.note.service.MsNoteEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Roadmap item 56: history, export and bulk delete built from an entity's declaration and its records. */
class EntityFeaturesTest {

    private static final EntityDefinition NOTES = MsNoteEntity.DEFINITION;

    /** Records 1 and 2 exist; 3 belongs to someone else; deletes are remembered. */
    static final class FakeRecords implements EntityRecords {
        private final String entity;
        final List<Long> deleted = new ArrayList<>();

        FakeRecords(String entity) {
            this.entity = entity;
        }

        public String entity() { return entity; }

        public void requireVisible(long id) {
            if (id > 2) throw ApiException.notFound(ErrorCode.NOT_FOUND, "Запись не найдена");
        }

        public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search) {
            return KeysetPage.of(List.of(Map.of("title", search == null ? "" : search)), null, false, 1);
        }

        public void delete(long id) {
            requireVisible(id);
            deleted.add(id);
        }
    }

    static FakeRecords records(String entity) {
        return new FakeRecords(entity);
    }

    static EntityRegistry notesRegistry() {
        return new EntityRegistry(List.of(NOTES), List.of(), List.of(records(NOTES.code())));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void declarationRefusesCapabilitiesItCannotKeep() {
        FormField title = FormField.of("title", "t", FormFieldType.TEXT);
        List<FormSection> layout = List.of(new FormSection("main", "m", List.of("title")));
        List<EntityAction> create = List.of(new EntityAction("create", "create"));

        assertThatThrownBy(() -> new EntityDefinition("x", "x", "x", null, null, List.of(title), layout, create,
                Set.of(EntityCapability.HISTORY))).hasMessageContaining("audit table");
        assertThatThrownBy(() -> new EntityDefinition("x", "x", null, null, null, List.of(title), layout, create,
                Set.of(EntityCapability.EXPORT))).hasMessageContaining("list");
        assertThatThrownBy(() -> new EntityDefinition("x", "x", "x", null, null, List.of(title), layout, create,
                Set.of(EntityCapability.BULK))).hasMessageContaining("delete action");
    }

    @Test
    void anEntityPromisingRecordFeaturesNeedsItsRecordsAtStart() {
        assertThatThrownBy(() -> new EntityRegistry(List.of(NOTES)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EntityRecords");
        assertThatThrownBy(() -> new EntityRegistry(List.of(NOTES), List.of(), List.of(records("ms.unknown"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("undeclared");
    }

    @Test
    void historyComesFromTheDeclaration() {
        RecordHistorySource source = notesRegistry().historySources().getFirst();

        assertThat(source.key()).isEqualTo("ms.notes");
        assertThat(source.tableName()).isEqualTo("ms_notes");
        assertThat(source.form() + "." + source.action()).isEqualTo("notes.view");
        assertThat(source.fieldLabels()).containsEntry("isPinned", "notes.col.pinned").containsEntry("title", "notes.col.title");
        source.requireVisible("1");
        for (String hidden : List.of("3", "x")) {
            assertThatThrownBy(() -> source.requireVisible(hidden))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    @Test
    void exportComesFromTheDeclarationUnderTheListCode() {
        QueryListExporter exporter = notesRegistry().exporters().getFirst();

        assertThat(exporter.code()).isEqualTo("ms.notes");
        assertThat(exporter.options()).isEmpty();
        List<Object> items = List.copyOf(exporter.page(10, null, null, null, "план", Map.of()).items());
        assertThat(items).containsExactly(Map.of("title", "план"));
    }

    @Test
    void bulkDeleteRunsTheModuleDeleteRecordByRecordWithTheDeclaredRight() {
        FakeRecords records = records(NOTES.code());
        EntityBulkController controller = new EntityBulkController(
                new EntityRegistry(List.of(NOTES), List.of(), List.of(records)));
        SecurityContext.setPrincipal(principal(Set.of("notes.view", "notes.delete")));

        BulkResult result = controller.bulk("ms.notes", new BulkRequest("delete", List.of(1L, 3L, 2L), null)).getBody();

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.results().get(1).code()).isEqualTo("not_found");
        assertThat(records.deleted).containsExactly(1L, 2L);
    }

    @Test
    void bulkRefusesWithoutTheRightAnUnknownActionAndAHiddenEntity() {
        EntityBulkController controller = new EntityBulkController(notesRegistry());
        BulkRequest delete = new BulkRequest("delete", List.of(1L), null);

        SecurityContext.setPrincipal(principal(Set.of("notes.view")));
        assertThatThrownBy(() -> controller.bulk("ms.notes", delete))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        assertThatThrownBy(() -> controller.bulk("ms.notes", new BulkRequest("archive", List.of(1L), null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        SecurityContext.setPrincipal(principal(Set.of("tasks.items.view")));
        for (String code : List.of("ms.notes", "nope")) {
            assertThatThrownBy(() -> controller.bulk(code, delete))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    private static SecurityContext.KauthPrincipal principal(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                10L, "viewer", "viewer@example.invalid", 20L, false, permissions, 1L, false, 0, null);
    }
}
