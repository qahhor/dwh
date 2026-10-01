package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityRecords;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.FormFieldType;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository.CustomFieldRecord;
import com.smartup24.cms.instance.md.service.MdCustomFieldFormFields;
import com.smartup24.cms.instance.md.service.MdCustomFieldQueryFields;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Plan 10/10, item 5.0: a field of an entity's form and the field of its list with the same name are one field —
 * the same kind of value, the same label, the same options and the same reference target. Otherwise the form offers
 * a choice the list shows as raw text (the note colour was a select in the form and free text in the list), or the
 * filter takes values the form refuses.
 */
class EntityFieldContractTest {

    @Test
    void everyDeclaredFormFieldMatchesItsListField() throws Exception {
        Map<String, QueryList> lists =
                declaredLists().stream().collect(Collectors.toMap(QueryList::code, Function.identity()));
        List<EntityDefinition> entities = EntityActionPermissionContractTest.declaredEntities();
        assertThat(entities).isNotEmpty();

        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (EntityDefinition entity : entities) {
            if (entity.listCode() == null) continue;
            QueryList list = lists.get(entity.listCode());
            if (list == null) {
                problems.add(entity.code() + ": no list " + entity.listCode());
                continue;
            }
            if (!Objects.equals(entity.customEntity(), list.customEntity())) {
                problems.add(entity.code() + ": custom fields of " + entity.customEntity() + " in the form, "
                        + list.customEntity() + " in the list");
            }
            for (FormField field : entity.fields()) {
                Optional<QueryField> listed = list.field(field.key());
                if (listed.isPresent()) {
                    compared++;
                    problems.addAll(mismatches(entity.code(), field, listed.get()));
                }
            }
        }
        assertThat(compared).as("form fields that have a list field").isGreaterThanOrEqualTo(4);
        assertThat(problems).isEmpty();
    }

    /** The custom fields of every type: the same key, label, kind, options and target in the form and the list. */
    @Test
    void everyCustomFieldTypeIsTheSameFieldInTheFormAndTheList() throws Exception {
        MdCustomFieldService service = mock(MdCustomFieldService.class);
        List<CustomFieldRecord> definitions = List.of(
                record("topic", "string", null),
                record("grade", "number", null),
                record("hired", "date", null),
                record("due_at", "datetime", null),
                record("call_time", "time", null),
                record("remote", "boolean", null),
                record("stage", "select", "[\"a\",\"b\"]"),
                record("note", "select", null),
                record("owner_id", "user_ref", null));
        when(service.getFields("NOTE")).thenReturn(definitions);
        when(service.parseSelectOptions("[\"a\",\"b\"]")).thenReturn(List.of("a", "b"));
        when(service.parseSelectOptions(null)).thenReturn(List.of());

        EntityDefinition notes = entity("ms.notes");
        QueryList noteList = new QueryListRegistry(declaredLists(), List.of(new MdCustomFieldQueryFields(service)))
                .get(notes.listCode());
        EntityRecords records = new EntityRecords() {
            public String entity() {
                return notes.code();
            }

            public void requireVisible(long id) {}
        };
        EntityDefinition resolved = new EntityRegistry(
                        List.of(notes), List.of(new MdCustomFieldFormFields(service)), List.of(records))
                .resolve(notes);

        List<FormField> custom = resolved.fields().stream()
                .filter(field -> field.attribute() != null)
                .toList();
        assertThat(custom).hasSize(definitions.size());
        List<String> problems = new ArrayList<>();
        for (FormField field : custom) {
            Optional<QueryField> listed = noteList.field(field.key());
            if (listed.isEmpty()) {
                problems.add(field.key() + ": not in the list");
                continue;
            }
            problems.addAll(mismatches("custom", field, listed.get()));
            if (!Objects.equals(field.attribute(), listed.get().attribute())) {
                problems.add(field.key() + ": attribute " + field.attribute() + " / "
                        + listed.get().attribute());
            }
        }
        assertThat(problems).isEmpty();
    }

    /** What differs between a form field and its list field, as readable lines; empty when they agree. */
    static List<String> mismatches(String entity, FormField form, QueryField list) {
        String at = entity + "." + form.key() + ": ";
        List<String> problems = new ArrayList<>();
        if (!compatible(form, list)) {
            problems.add(at + "form " + form.type() + " / list " + list.type());
        }
        if (!form.labelKey().equals(list.labelKey()) || !Objects.equals(form.label(), list.label())) {
            problems.add(at + "label " + form.labelKey() + form.label() + " / " + list.labelKey() + list.label());
        }
        if (form.type() == FormFieldType.SELECT
                && (!form.options().equals(list.enumValues())
                        || !Objects.equals(form.optionLabelPrefix(), list.enumLabelPrefix()))) {
            problems.add(at + "options " + form.options() + " " + form.optionLabelPrefix() + " / " + list.enumValues()
                    + " " + list.enumLabelPrefix());
        }
        if (!Objects.equals(form.ref(), list.ref())) {
            problems.add(at + "reference " + form.ref() + " / " + list.ref());
        }
        return problems;
    }

    /** The list type each form type is shown, filtered and exported as. */
    static boolean compatible(FormField form, QueryField list) {
        QueryFieldType type = list.type();
        return switch (form.type()) {
            case TEXT, TEXTAREA, MARKDOWN -> type == QueryFieldType.TEXT;
            case NUMBER -> type == QueryFieldType.NUMBER;
            case DATE -> type == QueryFieldType.DATE;
            case DATETIME -> type == QueryFieldType.INSTANT;
            case TIME -> type == QueryFieldType.TIME;
            case BOOLEAN -> type == QueryFieldType.BOOLEAN;
            case SELECT -> type == QueryFieldType.ENUM;
            case REF -> (type == QueryFieldType.NUMBER || type == QueryFieldType.TEXT) && list.ref() != null;
        };
    }

    @Test
    void theRuleCatchesTheOldNoteColour() {
        FormField color = FormField.select("color", "notes.col.color", List.of("blue", "red"), "notes.color_");
        QueryField text = QueryField.of("color", "notes.col.color", QueryFieldType.TEXT, "n.color");
        QueryField choice = QueryField.enumeration("color", "notes.col.color", "n.color", List.of("blue", "red"), "x.");

        assertThat(mismatches("ms.notes", color, text)).hasSize(2);
        assertThat(mismatches("ms.notes", color, choice))
                .singleElement()
                .asString()
                .contains("options");
    }

    private static EntityDefinition entity(String code) throws Exception {
        return EntityActionPermissionContractTest.declaredEntities().stream()
                .filter(entity -> entity.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private static CustomFieldRecord record(String code, String type, String options) {
        return new CustomFieldRecord(
                1L, "NOTE", code, "Поле " + code, type, false, null, options, 0, Instant.EPOCH, 1L);
    }

    /** The lists the application declares: the {@code @Bean QueryList} methods without parameters. */
    static List<QueryList> declaredLists() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        List<QueryList> lists = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.smartup24.cms.instance")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class)
                        && method.getReturnType() == QueryList.class
                        && method.getParameterCount() == 0) {
                    var ctor = type.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    method.setAccessible(true);
                    lists.add((QueryList) method.invoke(ctor.newInstance()));
                }
            }
        }
        return lists;
    }
}
