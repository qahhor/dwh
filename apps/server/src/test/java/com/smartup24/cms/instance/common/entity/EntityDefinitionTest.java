package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.platform.api.entity.EntityDefinition.FormSection;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ADR-0019, roadmap item 54: the entity declaration, its validation and {@code form-meta}. */
class EntityDefinitionTest {

    private static final EntityDefinition NOTES = MsNoteEntity.DEFINITION;

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void declarationRejectsFieldsOutsideTheLayoutAndDuplicates() {
        FormField title = FormField.of("title", "t", FieldType.TEXT);
        FormField body = FormField.of("body", "b", FieldType.TEXT);

        assertThatThrownBy(() -> entity(List.of(title, body), List.of(new FormSection("main", "m", List.of("title")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity(List.of(title, title), List.of(new FormSection("main", "m", List.of("title")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity(
                        List.of(title),
                        List.of(
                                new FormSection("a", "a", List.of("title")),
                                new FormSection("b", "b", List.of("title")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityDefinition(
                        "x",
                        "x",
                        null,
                        null,
                        null,
                        null,
                        List.of(title),
                        List.of(new FormSection("main", "m", List.of("title"))),
                        List.of(),
                        Set.of(EntityCapability.CUSTOM_FIELDS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldRejectsABadKeyAndMismatchedOptionsOrRef() {
        assertThatThrownBy(() -> FormField.of("Bad-key", "l", FieldType.TEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormField.of("state", "l", FieldType.SELECT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormField.of("owner", "l", FieldType.REF))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FormField.of("owner", "l", FieldType.NUMBER)
                        .refersTo(QueryRef.paged("/iam/users", "name"))
                        .type())
                .isEqualTo(FieldType.REF);
    }

    @Test
    void validatorAddressesEveryProblemToItsField() {
        Map<String, Object> values = new HashMap<>();
        values.put("title", "x".repeat(256));
        values.put("color", "orange");
        values.put("isPinned", "maybe");

        List<FieldErrorItem> problems = EntityValidator.problems(NOTES, values, false);

        assertThat(problems)
                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("title", EntityValidator.TOO_LONG),
                        org.assertj.core.groups.Tuple.tuple("color", EntityValidator.INVALID),
                        org.assertj.core.groups.Tuple.tuple("isPinned", EntityValidator.INVALID));
    }

    @Test
    void requiredFieldIsRequiredOnCreateAndWhenAnUpdateClearsIt() {
        assertThat(EntityValidator.problems(NOTES, Map.of(), false))
                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("title", EntityValidator.REQUIRED),
                        org.assertj.core.groups.Tuple.tuple("color", EntityValidator.REQUIRED));
        assertThat(EntityValidator.problems(NOTES, Map.of(), true)).isEmpty();
        assertThat(EntityValidator.problems(NOTES, Map.of("title", "  "), true))
                .extracting(FieldErrorItem::code)
                .containsExactly(EntityValidator.REQUIRED);

        assertThatThrownBy(() -> EntityValidator.check(NOTES, Map.of("color", "blue"), false))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors())
                            .extracting(FieldErrorItem::field)
                            .containsExactly("title");
                });
    }

    @Test
    void validatorChecksNumbersDatesAndPatterns() {
        FormField amount = FormField.of("amount", "a", FieldType.NUMBER).range(BigDecimal.ZERO, BigDecimal.TEN);
        FormField due = FormField.of("due", "d", FieldType.DATE);
        FormField code = FormField.of("code", "c", FieldType.TEXT).matching("[A-Z]{3}");
        EntityDefinition entity = entity(
                List.of(amount, due, code), List.of(new FormSection("main", "m", List.of("amount", "due", "code"))));

        assertThat(EntityValidator.problems(entity, Map.of("amount", "11", "due", "2026-13-01", "code", "ab"), false))
                .extracting(FieldErrorItem::code)
                .containsExactly(EntityValidator.OUT_OF_RANGE, EntityValidator.INVALID, EntityValidator.INVALID);
        assertThat(EntityValidator.problems(entity, Map.of("amount", "x"), false))
                .extracting(FieldErrorItem::code)
                .containsExactly(EntityValidator.INVALID);
        assertThat(EntityValidator.problems(entity, Map.of("amount", 5, "due", "2026-09-26", "code", "ABC"), false))
                .isEmpty();
    }

    /** Plan 10/10, item 5.0: a moment carries its offset; a time of day is HH:mm or HH:mm:ss. */
    @Test
    void validatorChecksMomentsAndTimesOfDay() {
        FormField at = FormField.of("startsAt", "s", FieldType.DATETIME);
        FormField time = FormField.of("callTime", "t", FieldType.TIME);
        EntityDefinition entity =
                entity(List.of(at, time), List.of(new FormSection("main", "m", List.of("startsAt", "callTime"))));

        for (String moment : List.of("2026-10-01T09:30:00Z", "2026-10-01T09:30+05:00", "2026-10-01T09:30:15.250Z")) {
            assertThat(EntityValidator.problems(entity, Map.of("startsAt", moment), false))
                    .as(moment)
                    .isEmpty();
        }
        for (String moment : List.of("2026-10-01T09:30", "2026-10-01", "2026-13-01T09:30Z", "soon")) {
            assertThat(EntityValidator.problems(entity, Map.of("startsAt", moment), false))
                    .as(moment)
                    .extracting(FieldErrorItem::code, FieldErrorItem::messageKey)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(
                            EntityValidator.INVALID, "error.field.datetime_required"));
        }
        for (String ok : List.of("09:30", "23:59:59", "00:00")) {
            assertThat(EntityValidator.problems(entity, Map.of("callTime", ok), false))
                    .as(ok)
                    .isEmpty();
        }
        for (String bad : List.of("24:00", "9:30", "09:60", "09:30:00.5", "noon")) {
            assertThat(EntityValidator.problems(entity, Map.of("callTime", bad), false))
                    .as(bad)
                    .extracting(FieldErrorItem::messageKey)
                    .containsExactly("error.field.time_required");
        }
        assertThat(FieldType.DATETIME.wire()).isEqualTo("datetime");
        assertThat(FieldType.TIME.wire()).isEqualTo("time");
    }

    @Test
    void registryAddsCustomFieldsInTheirOwnSectionAndSkipsTakenKeys() {
        FormFieldExtender extender = entity -> List.of(
                FormField.of("cfBudget", "", FieldType.NUMBER).custom("Бюджет", "budget"),
                FormField.of("title", "", FieldType.TEXT).custom("Дубль", "title"));
        EntityRegistry registry = new EntityRegistry(List.of(NOTES), List.of(extender));

        EntityDefinition resolved = registry.find(NOTES.code()).orElseThrow();

        assertThat(resolved.fields()).extracting(FormField::key).endsWith("isPinned", "cfBudget");
        assertThat(resolved.fieldsByKey().get("title").attribute()).isNull();
        assertThat(resolved.layout().getLast().key()).isEqualTo(EntityRegistry.CUSTOM_SECTION);
        assertThat(resolved.layout().getLast().fields()).containsExactly("cfBudget");
        // A custom field is the custom field service's to check, not the declared validator's.
        assertThat(EntityValidator.problems(resolved, Map.of("title", "ok", "color", "blue", "cfBudget", "x"), false))
                .isEmpty();
        assertThat(registry.find("unknown")).isEmpty();
        assertThatThrownBy(() -> new EntityRegistry(List.of(NOTES, NOTES))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void formMetaGivesOnlyTheActionsTheViewerMayTake() {
        SecurityContext.setPrincipal(principal(Set.of("notes.view", "notes.update")));
        FormMetaController controller = new FormMetaController(EntityFeaturesTest.notesRegistry());

        FormMetaController.FormMeta meta = controller.get(NOTES.code()).getBody();

        assertThat(meta.actions()).containsExactly("update");
        assertThat(meta.listCode()).isEqualTo("ms.notes");
        assertThat(meta.capabilities())
                .containsExactly("archive", "bulk", "custom_fields", "export", "history", "saved_views", "search");
        FormMetaController.FormFieldMeta title = meta.fields().getFirst();
        assertThat(title.type()).isEqualTo("text");
        assertThat(title.required()).isTrue();
        assertThat(title.maxLength()).isEqualTo(255);
        assertThat(meta.fields().get(2).options()).contains("default", "red");
        assertThat(meta.layout())
                .extracting(FormMetaController.FormSectionMeta::key)
                .containsExactly("main", "settings");
    }

    @Test
    void formMetaAnswersTheSame404ForUnknownAndHiddenEntities() {
        SecurityContext.setPrincipal(principal(Set.of("tasks.items.view")));
        FormMetaController controller = new FormMetaController(EntityFeaturesTest.notesRegistry());

        for (String code : List.of(NOTES.code(), "nope")) {
            assertThatThrownBy(() -> controller.get(code)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(e.getMessageKey()).isEqualTo("error.common.entity_not_found");
            });
        }
    }

    private static EntityDefinition entity(List<FormField> fields, List<FormSection> layout) {
        return new EntityDefinition(
                "x.items",
                "x",
                null,
                null,
                null,
                null,
                fields,
                layout,
                List.of(new EntityAction("create", "create")),
                Set.of());
    }

    private static SecurityContext.KauthPrincipal principal(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                10L, "viewer", "viewer@example.invalid", 20L, false, permissions, 1L, false, 0, null);
    }
}
