package com.greenwhite.dwh.instance.common.entity;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.EntityAction;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.FormSection;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.query.QueryRef;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.ms.note.service.MsNoteEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-0019, roadmap item 54: the entity declaration, its validation and {@code form-meta}. */
class EntityDefinitionTest {

    private static final EntityDefinition NOTES = MsNoteEntity.DEFINITION;

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void declarationRejectsFieldsOutsideTheLayoutAndDuplicates() {
        FormField title = FormField.of("title", "t", FormFieldType.TEXT);
        FormField body = FormField.of("body", "b", FormFieldType.TEXT);

        assertThatThrownBy(() -> entity(List.of(title, body), List.of(new FormSection("main", "m", List.of("title")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity(List.of(title, title), List.of(new FormSection("main", "m", List.of("title")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity(List.of(title), List.of(
                new FormSection("a", "a", List.of("title")), new FormSection("b", "b", List.of("title")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityDefinition("x", "x", null, null, null, null, null, List.of(title),
                List.of(new FormSection("main", "m", List.of("title"))), List.of(),
                Set.of(EntityCapability.CUSTOM_FIELDS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldRejectsABadKeyAndMismatchedOptionsOrRef() {
        assertThatThrownBy(() -> FormField.of("Bad-key", "l", FormFieldType.TEXT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormField.of("state", "l", FormFieldType.SELECT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormField.of("owner", "l", FormFieldType.REF)).isInstanceOf(IllegalArgumentException.class);
        assertThat(FormField.of("owner", "l", FormFieldType.NUMBER).refersTo(QueryRef.paged("/iam/users", "name")).type())
                .isEqualTo(FormFieldType.REF);
    }

    @Test
    void validatorAddressesEveryProblemToItsField() {
        Map<String, Object> values = new HashMap<>();
        values.put("title", "x".repeat(256));
        values.put("color", "orange");
        values.put("isPinned", "maybe");

        List<FieldErrorItem> problems = EntityValidator.problems(NOTES, values, false);

        assertThat(problems).extracting(FieldErrorItem::field, FieldErrorItem::code).containsExactly(
                org.assertj.core.groups.Tuple.tuple("title", EntityValidator.TOO_LONG),
                org.assertj.core.groups.Tuple.tuple("color", EntityValidator.INVALID),
                org.assertj.core.groups.Tuple.tuple("isPinned", EntityValidator.INVALID));
    }

    @Test
    void requiredFieldIsRequiredOnCreateAndWhenAnUpdateClearsIt() {
        assertThat(EntityValidator.problems(NOTES, Map.of(), false))
                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("title", EntityValidator.REQUIRED));
        assertThat(EntityValidator.problems(NOTES, Map.of(), true)).isEmpty();
        assertThat(EntityValidator.problems(NOTES, Map.of("title", "  "), true))
                .extracting(FieldErrorItem::code).containsExactly(EntityValidator.REQUIRED);

        assertThatThrownBy(() -> EntityValidator.check(NOTES, Map.of("color", "blue"), false))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(FieldErrorItem::field).containsExactly("title");
                });
    }

    @Test
    void validatorChecksNumbersDatesAndPatterns() {
        FormField amount = FormField.of("amount", "a", FormFieldType.NUMBER).range(BigDecimal.ZERO, BigDecimal.TEN);
        FormField due = FormField.of("due", "d", FormFieldType.DATE);
        FormField code = FormField.of("code", "c", FormFieldType.TEXT).matching("[A-Z]{3}");
        EntityDefinition entity = entity(List.of(amount, due, code),
                List.of(new FormSection("main", "m", List.of("amount", "due", "code"))));

        assertThat(EntityValidator.problems(entity, Map.of("amount", "11", "due", "2026-13-01", "code", "ab"), false))
                .extracting(FieldErrorItem::code)
                .containsExactly(EntityValidator.OUT_OF_RANGE, EntityValidator.INVALID, EntityValidator.INVALID);
        assertThat(EntityValidator.problems(entity, Map.of("amount", "x"), false))
                .extracting(FieldErrorItem::code).containsExactly(EntityValidator.INVALID);
        assertThat(EntityValidator.problems(entity, Map.of("amount", 5, "due", "2026-09-26", "code", "ABC"), false)).isEmpty();
    }

    @Test
    void registryAddsCustomFieldsInTheirOwnSectionAndSkipsTakenKeys() {
        FormFieldExtender extender = entity -> List.of(
                FormField.of("cfBudget", "", FormFieldType.NUMBER).custom("Бюджет", "budget"),
                FormField.of("title", "", FormFieldType.TEXT).custom("Дубль", "title"));
        EntityRegistry registry = new EntityRegistry(List.of(NOTES), List.of(extender), List.of(EntityFeaturesTest.records(NOTES.code())));

        EntityDefinition resolved = registry.find(NOTES.code()).orElseThrow();

        assertThat(resolved.fields()).extracting(FormField::key).endsWith("isPinned", "cfBudget");
        assertThat(resolved.fieldsByKey().get("title").attribute()).isNull();
        assertThat(resolved.layout().getLast().key()).isEqualTo(EntityRegistry.CUSTOM_SECTION);
        assertThat(resolved.layout().getLast().fields()).containsExactly("cfBudget");
        // A custom field is the custom field service's to check, not the declared validator's.
        assertThat(EntityValidator.problems(resolved, Map.of("title", "ok", "cfBudget", "x"), false)).isEmpty();
        assertThat(registry.find("unknown")).isEmpty();
        assertThatThrownBy(() -> new EntityRegistry(List.of(NOTES, NOTES))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void formMetaGivesOnlyTheActionsTheViewerMayTake() {
        SecurityContext.setPrincipal(principal(Set.of("notes.view", "notes.update")));
        FormMetaController controller = new FormMetaController(EntityFeaturesTest.notesRegistry());

        FormMetaController.FormMeta meta = controller.get(NOTES.code()).getBody();

        assertThat(meta.actions()).containsExactly("update", "pin");
        assertThat(meta.listCode()).isEqualTo("ms.notes");
        assertThat(meta.capabilities()).containsExactly("bulk", "custom_fields", "export", "history", "saved_views");
        FormMetaController.FieldMeta title = meta.fields().getFirst();
        assertThat(title.type()).isEqualTo("text");
        assertThat(title.required()).isTrue();
        assertThat(title.maxLength()).isEqualTo(255);
        assertThat(meta.fields().get(2).options()).contains("default", "red");
        assertThat(meta.layout()).extracting(FormMetaController.SectionMeta::key).containsExactly("main", "settings");
    }

    @Test
    void formMetaAnswersTheSame404ForUnknownAndHiddenEntities() {
        SecurityContext.setPrincipal(principal(Set.of("tasks.items.view")));
        FormMetaController controller = new FormMetaController(EntityFeaturesTest.notesRegistry());

        for (String code : List.of(NOTES.code(), "nope")) {
            assertThatThrownBy(() -> controller.get(code))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                        assertThat(e.getMessage()).isEqualTo("ENTITY_NOT_FOUND");
                    });
        }
    }

    private static EntityDefinition entity(List<FormField> fields, List<FormSection> layout) {
        return new EntityDefinition("x.items", "x", null, null, null, null, null, fields, layout,
                List.of(new EntityAction("create", "create")), Set.of());
    }

    private static SecurityContext.KauthPrincipal principal(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                10L, "viewer", "viewer@example.invalid", 20L, false, permissions, 1L, false, 0, null);
    }
}
