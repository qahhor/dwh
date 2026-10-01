package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkRequest;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkResult;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.instance.common.entity.EntityDefinition.FormSection;
import com.smartup24.cms.instance.common.entity.field.FieldType;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.history.RecordHistorySource;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Roadmap item 56: history, export and bulk delete built from an entity's declaration and its records. */
class EntityFeaturesTest {

    private static final EntityDefinition NOTES = MsNoteEntity.DEFINITION;

    /** Records 1 and 2 exist; 3 belongs to someone else; deletes are remembered. */
    static final class FakeRecords implements EntityRecords {
        private final String entity;
        final List<Long> deleted = new ArrayList<>();
        final List<Long> archived = new ArrayList<>();

        FakeRecords(String entity) {
            this.entity = entity;
        }

        public String entity() {
            return entity;
        }

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

        @Override
        public void archive(long id) {
            requireVisible(id);
            archived.add(id);
        }
    }

    static FakeRecords records(String entity) {
        return new FakeRecords(entity);
    }

    static EntityRegistry notesRegistry() {
        return registry(List.of(NOTES), List.of(), records(NOTES.code()));
    }

    /**
     * A registry whose entities with a table keep their records in these fakes, as the general runtime keeps them
     * (ADR-0032, 6.5): the fakes stand in for its {@link EntityRecordStore}.
     */
    static EntityRegistry registry(
            List<EntityDefinition> entities, List<FormFieldExtender> extenders, EntityRecords... records) {
        EntityRecordStore store = store(records);
        return new EntityRegistry(entities, extenders, List.of(), List.of(), List.of(), () -> store);
    }

    /** A store that answers for each entity with the fake records named after it. */
    static EntityRecordStore store(EntityRecords... records) {
        Map<String, EntityRecords> byEntity = new java.util.HashMap<>();
        for (EntityRecords one : records) {
            byEntity.put(one.entity(), one);
        }
        return new EntityRecordStore() {
            @Override
            public void requireVisible(EntityDefinition entity, long id) {
                byEntity.get(entity.code()).requireVisible(id);
            }

            @Override
            public KeysetPage<?> page(
                    EntityDefinition entity, int limit, String cursor, String filter, String sort, String search) {
                return byEntity.get(entity.code()).page(limit, cursor, filter, sort, search);
            }

            @Override
            public void delete(EntityDefinition entity, long id) {
                byEntity.get(entity.code()).delete(id);
            }

            @Override
            public void archive(EntityDefinition entity, long id) {
                byEntity.get(entity.code()).archive(id);
            }
        };
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void declarationRefusesCapabilitiesItCannotKeep() {
        FormField title = FormField.of("title", "t", FieldType.TEXT);
        List<FormSection> layout = List.of(new FormSection("main", "m", List.of("title")));
        List<EntityAction> create = List.of(new EntityAction("create", "create"));

        assertThatThrownBy(() -> new EntityDefinition(
                        "x",
                        "x",
                        null,
                        null,
                        null,
                        null,
                        List.of(title),
                        layout,
                        create,
                        Set.of(EntityCapability.HISTORY)))
                .hasMessageContaining("audit table");
        assertThatThrownBy(() -> new EntityDefinition(
                        "x",
                        "x",
                        null,
                        null,
                        null,
                        null,
                        List.of(title),
                        layout,
                        create,
                        Set.of(EntityCapability.EXPORT)))
                .hasMessageContaining("list");
        assertThatThrownBy(() -> new EntityDefinition(
                        "x",
                        "x",
                        null,
                        null,
                        null,
                        null,
                        List.of(title),
                        layout,
                        create,
                        Set.of(EntityCapability.BULK)))
                .hasMessageContaining("delete action");
    }

    /**
     * ADR-0032, 6.5: the runtime keeps the records of an entity with a table, so a module bean of them is refused; an
     * entity without a table that promises history, export or bulk actions needs its module's records at start.
     */
    @Test
    void recordsComeFromTheRuntimeForATableAndFromTheModuleOtherwise() {
        assertThat(new EntityRegistry(List.of(NOTES)).records(NOTES.code())).isPresent();
        assertThatThrownBy(() -> new EntityRegistry(
                        List.of(NOTES), List.of(), List.of(records(NOTES.code())), List.of(), List.of(), () -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("general runtime");
        assertThatThrownBy(() -> new EntityRegistry(
                        List.of(NOTES), List.of(), List.of(records("ms.unknown")), List.of(), List.of(), () -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("undeclared");
        FormField title = FormField.of("title", "t", FieldType.TEXT);
        EntityDefinition formOnly = new EntityDefinition(
                "x.form",
                "x",
                null,
                "x_audit",
                null,
                null,
                List.of(title),
                List.of(new FormSection("main", "m", List.of("title"))),
                List.of(),
                Set.of(EntityCapability.HISTORY));
        assertThatThrownBy(() -> new EntityRegistry(List.of(formOnly)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EntityRecords");
    }

    @Test
    void historyComesFromTheDeclaration() {
        RecordHistorySource source = notesRegistry().historySources().getFirst();

        assertThat(source.key()).isEqualTo("ms.notes");
        assertThat(source.tableName()).isEqualTo("ms_notes");
        assertThat(source.form() + "." + source.action()).isEqualTo("notes.view");
        assertThat(source.fieldLabels())
                .containsEntry("isPinned", "notes.col.pinned")
                .containsEntry("title", "notes.col.title");
        source.requireVisible("1");
        for (String hidden : List.of("3", "x")) {
            assertThatThrownBy(() -> source.requireVisible(hidden))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    /** Plan 10/10, item 5.0: every field is named, a custom field added after start by its own name. */
    @Test
    void historyNamesEveryFieldIncludingCustomFieldsAddedLater() {
        List<FormField> custom = new ArrayList<>();
        FormFieldExtender extender = entity -> List.copyOf(custom);
        RecordHistorySource source = registry(List.of(NOTES), List.of(extender), records(NOTES.code()))
                .historySources()
                .getFirst();
        assertThat(source.fieldLabels())
                .containsOnlyKeys("title", "contentMd", "color", "isPinned", "archived")
                .containsEntry("contentMd", "notes.col.content");
        assertThat(source.fieldNames()).isEmpty();

        custom.add(FormField.of("cfRegion", "", FieldType.TEXT).custom("Регион", "region"));

        assertThat(source.fieldNames()).containsExactly(Map.entry("cfRegion", "Регион"));
        assertThat(source.fieldLabels()).doesNotContainKey("cfRegion");
    }

    @Test
    void exportComesFromTheDeclarationUnderTheListCode() {
        QueryListExporter exporter = notesRegistry().exporters().getFirst();

        assertThat(exporter.code()).isEqualTo("ms.notes");
        assertThat(exporter.options()).isEmpty();
        List<Object> items = List.copyOf(
                exporter.page(10, null, null, null, "план", Map.of()).items());
        assertThat(items).containsExactly(Map.of("title", "план"));
    }

    @Test
    void bulkDeleteRunsTheModuleDeleteRecordByRecordWithTheDeclaredRight() {
        FakeRecords records = records(NOTES.code());
        EntityBulkController controller = new EntityBulkController(registry(List.of(NOTES), List.of(), records));
        SecurityContext.setPrincipal(principal(Set.of("notes.view", "notes.delete")));

        BulkResult result = controller
                .bulk("ms.notes", new BulkRequest("delete", List.of(1L, 3L, 2L), null))
                .getBody();

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.results().get(1).code()).isEqualTo("not_found");
        assertThat(records.deleted).containsExactly(1L, 2L);
    }

    /** Plan 10/10, item 5.3 (ADR-0032, 5.4): the bulk archive runs the module's single archive, record by record. */
    @Test
    void bulkArchiveRunsTheModuleArchiveRecordByRecord() {
        FakeRecords records = records(NOTES.code());
        EntityBulkController controller = new EntityBulkController(registry(List.of(NOTES), List.of(), records));
        SecurityContext.setPrincipal(principal(Set.of("notes.view", "notes.delete")));

        BulkResult result = controller
                .bulk("ms.notes", new BulkRequest("archive", List.of(2L, 3L), null))
                .getBody();

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.results().get(1).code()).isEqualTo("not_found");
        assertThat(records.archived).containsExactly(2L);
        assertThat(records.deleted).isEmpty();
    }

    @Test
    void bulkRefusesWithoutTheRightAnUnknownActionAndAHiddenEntity() {
        EntityBulkController controller = new EntityBulkController(notesRegistry());
        BulkRequest delete = new BulkRequest("delete", List.of(1L), null);

        SecurityContext.setPrincipal(principal(Set.of("notes.view")));
        assertThatThrownBy(() -> controller.bulk("ms.notes", delete))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        // The archive needs the right's delete (ADR-0032, 19, question 11: the default, an assumption).
        assertThatThrownBy(() -> controller.bulk("ms.notes", new BulkRequest("archive", List.of(1L), null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        assertThatThrownBy(() -> controller.bulk("ms.notes", new BulkRequest("purge", List.of(1L), null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        SecurityContext.setPrincipal(principal(Set.of("tasks.items.view")));
        for (String code : List.of("ms.notes", "nope")) {
            assertThatThrownBy(() -> controller.bulk(code, delete))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    @Test
    void menuHasTheItemsOfEntitiesTheViewerMayOpen() {
        EntityMenuController controller = new EntityMenuController(notesRegistry());

        SecurityContext.setPrincipal(principal(Set.of("notes.view")));
        var items = controller.menu().getBody();
        assertThat(items).hasSize(1);
        assertThat(items.getFirst())
                .isEqualTo(new EntityMenuController.MenuItem(
                        "ms.notes", "notes", "/notes", "nav.notes", "description", "workspace", 30, "notes"));

        SecurityContext.setPrincipal(principal(Set.of("tasks.items.view")));
        assertThat(controller.menu().getBody()).isEmpty();
    }

    @Test
    void aMenuItemWithoutItsOwnRouteLeadsToTheGeneralScreen() {
        // ADR-0032, 7.1: a new entity appears in the UI with no screen code; only a board or a wizard names a route.
        var general = new EntityDefinition.EntityMenu("nav.orders", "receipt", "workspace", 40, null);
        assertThat(general.route()).isNull();
        assertThat(general.routeFor("ex.orders")).isEqualTo("/e/ex.orders");

        var own = new EntityDefinition.EntityMenu("/notes", "nav.notes", "description", "workspace", 30, "notes");
        assertThat(own.routeFor("ms.notes")).isEqualTo("/notes");
    }

    @Test
    void rightsNameViewAndEveryDeclaredActionsRight() {
        FormField title = FormField.of("title", "t", FieldType.TEXT);
        List<FormSection> layout = List.of(new FormSection("main", "m", List.of("title")));
        var rights = new EntityDefinition.EntityRights("x", "x.rights.form", Map.of("view", "x.rights.view"));

        assertThatThrownBy(() -> new EntityDefinition(
                        "x",
                        "x",
                        null,
                        null,
                        rights,
                        null,
                        List.of(title),
                        layout,
                        List.of(new EntityAction("create", "create")),
                        Set.of()))
                .hasMessageContaining("right");
        assertThat(notesRegistry().rights("notes").orElseThrow().actionKeys())
                .containsKeys("view", "create", "update", "delete");
        assertThat(notesRegistry().rights("tasks.items")).isEmpty();
    }

    private static SecurityContext.KauthPrincipal principal(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                10L, "viewer", "viewer@example.invalid", 20L, false, permissions, 1L, false, 0, null);
    }
}
