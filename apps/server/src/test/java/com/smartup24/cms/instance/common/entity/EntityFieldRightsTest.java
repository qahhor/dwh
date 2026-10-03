package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.searchable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormFieldMeta;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormSectionMeta;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.history.RecordHistorySource;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldAccess;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.3, acceptance "a field-rights test covers form-meta, read, write and export" (ADR-0032, 5.2): a
 * field with {@code requires} does not exist for a viewer without the right — not in {@code form-meta}, not in the
 * list, its filter, sort and search, not in the export's columns, not in a record read, not in the history — and a
 * value sent for it is refused as an unknown field; a field with {@code readonlyUnless} is read-only for a viewer
 * without that right. A webhook has no viewer, so its data never holds a restricted field.
 */
class EntityFieldRightsTest {

    /** Salaries need {@code staff.salary}; the grade is set by holders of {@code staff.grade}. */
    static final EntityDefinition STAFF = Entity.define("x.staff", "staff")
            .table("x_staff", "s")
            .scope(EntityScope.orgUnit("org_unit_id", "created_by"))
            .field(text("name", "x.col.name")
                    .column("name")
                    .required()
                    .list(sortable().searchable()))
            .field(text("salaryNote", "x.col.salary_note")
                    .column("salary_note")
                    .requires("staff", "salary")
                    .list(searchable()))
            .field(number("salary", "x.col.salary")
                    .column("salary")
                    .requires("staff", "salary")
                    .list(sortable()))
            .field(select("grade", "x.col.grade", List.of("junior", "senior"), null)
                    .column("grade")
                    .readonlyUnless("staff", "grade"))
            .section("main", "m", "name", "grade")
            .section("pay", "p", "salaryNote", "salary")
            .actions("create", "update")
            .defaultSort("name", Entity.Sort.ASC)
            .capabilities(EntityCapability.EXPORT, EntityCapability.HISTORY)
            .build();

    private static final Set<String> VIEWER = Set.of("staff.view", "staff.create", "staff.update");

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                7L, "viewer", "viewer@example.test", 1L, false, permissions, 1L, false, 1L, null));
    }

    private static Set<String> withRights() {
        Set<String> all = new HashSet<>(VIEWER);
        all.add("staff.salary");
        all.add("staff.grade");
        return all;
    }

    @Test
    void theDeclarationKeepsTheFieldRights() {
        FieldAccess salary = EntityFieldRights.of(STAFF, "salary");
        assertThat(salary.restricted()).isTrue();
        assertThat(salary.guardsWriting()).isFalse();
        assertThat(EntityFieldRights.of(STAFF, "grade").guardsWriting()).isTrue();
        assertThat(EntityFieldRights.of(STAFF, "name")).isEqualTo(FieldAccess.OPEN);
        assertThat(EntityFieldRights.of(STAFF, "cfRegion")).as("a custom field").isEqualTo(FieldAccess.OPEN);
        assertThat(EntityFieldRights.restricted(STAFF)).containsExactly("salaryNote", "salary");
        // Only a form field is written, so only a form field is read-only.
        assertThatThrownBy(() -> text("rank", "x.col.rank")
                        .expression("s.rank")
                        .readonlyUnless("staff", "grade")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formMetaLeavesOutTheFieldAndMarksTheReadOnlyOne() {
        signIn(VIEWER);
        FormMeta form = FormMetaController.of(STAFF);

        assertThat(form.fields()).extracting(FormFieldMeta::key).containsExactly("name", "grade");
        assertThat(form.fields())
                .extracting(FormFieldMeta::key, FormFieldMeta::readonly)
                .containsExactly(tuple("name", false), tuple("grade", true));
        // A section left without fields goes, so the form does not show an empty group.
        assertThat(form.layout()).extracting(FormSectionMeta::key).containsExactly("main");

        signIn(withRights());
        FormMeta full = FormMetaController.of(STAFF);
        assertThat(full.fields())
                .extracting(FormFieldMeta::key)
                .containsExactly("name", "salaryNote", "salary", "grade");
        assertThat(full.fields()).noneMatch(FormFieldMeta::readonly);
        assertThat(full.layout()).extracting(FormSectionMeta::key).containsExactly("main", "pay");
    }

    @Test
    void theListAndItsExportLoseTheColumnAndItsFilterSortAndSearch() {
        QueryList list = EntityLists.queryList(STAFF);
        signIn(VIEWER);
        // query-meta and the export's columns are the viewer's fields of the list (ADR-0018).
        assertThat(list.viewerFields()).extracting(QueryField::key).containsExactly("name", "grade");
        assertThatThrownBy(() -> QueryCompiler.compile(
                        list, "[{\"field\":\"salary\",\"op\":\"gt\",\"value\":1}]", null, null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> QueryCompiler.compile(list, null, "-salary", null, null, null))
                .isInstanceOf(ApiException.class);
        QueryPlan searched = QueryCompiler.compile(list, null, null, null, null, "bonus");
        assertThat(searched.shows("salaryNote")).isFalse();
        assertThat(searched.where().sql()).doesNotContain("salary_note");

        signIn(withRights());
        assertThat(list.viewerFields())
                .extracting(QueryField::key)
                .containsExactly("name", "salaryNote", "salary", "grade");
        assertThat(QueryCompiler.compile(list, null, null, null, null, "bonus")
                        .where()
                        .sql())
                .contains("salary_note");
    }

    @Test
    void aRecordReadLosesTheProperty() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", 3L);
        record.put("name", "Ann");
        record.put("salary", 1200);
        record.put("salaryNote", "bonus");
        record.put("salary$label", "1 200");
        record.put("grade", "senior");
        record.put("labels", Map.of("salary", "1 200", "grade", "Senior"));

        signIn(VIEWER);
        assertThat(EntityFieldRights.project(STAFF, record))
                .containsOnlyKeys("id", "name", "grade", "labels")
                .containsEntry("labels", Map.of("grade", "Senior"));

        signIn(withRights());
        assertThat(EntityFieldRights.project(STAFF, record)).isEqualTo(record);
        // A webhook has no viewer: its data never holds a restricted field, whoever caused the change.
        assertThat(EntityFieldRights.forEveryone(STAFF, record)).containsOnlyKeys("id", "name", "grade", "labels");
    }

    @Test
    void theHistoryHidesTheField() {
        EntityRegistry registry = new EntityRegistry(List.of(STAFF), List.of());
        RecordHistorySource history = registry.historySources().getFirst();

        signIn(VIEWER);
        assertThat(history.hiddenFields()).containsExactly("salaryNote", "salary");
        signIn(withRights());
        assertThat(history.hiddenFields()).isEmpty();
    }

    @Test
    void aWriteOfTheFieldIsRefusedAsAnUnknownOneAndAReadOnlyValueMayNotChange() {
        signIn(VIEWER);
        assertThatThrownBy(() -> EntityFieldRights.checkWrite(STAFF, Map.of("name", "Ann", "salary", 900), null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .extracting(FieldErrorItem::field, FieldErrorItem::code, FieldErrorItem::messageKey)
                                .containsExactly(
                                        tuple("salary", EntityFieldRights.UNKNOWN_FIELD, "error.field.unknown")));

        Map<String, Object> current = Map.of("name", "Ann", "grade", "junior");
        assertThatThrownBy(() -> EntityFieldRights.checkWrite(STAFF, Map.of("grade", "senior"), current))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .extracting(FieldErrorItem::field, FieldErrorItem::code, FieldErrorItem::messageKey)
                                .containsExactly(tuple("grade", EntityFieldRights.READONLY, "error.field.readonly")));
        // The record sent back whole: an unchanged read-only value passes.
        EntityFieldRights.checkWrite(STAFF, Map.of("name", "Anna", "grade", "junior"), current);
        // A new record: any value of a read-only field is a change.
        assertThat(EntityFieldRights.writeProblems(STAFF, Map.of("grade", "junior"), null))
                .extracting(FieldErrorItem::code)
                .containsExactly(EntityFieldRights.READONLY);
        assertThat(EntityFieldRights.writeProblems(STAFF, Map.of("name", "Ann"), null))
                .isEmpty();

        signIn(withRights());
        EntityFieldRights.checkWrite(STAFF, Map.of("salary", 900, "grade", "senior"), current);
    }
}
