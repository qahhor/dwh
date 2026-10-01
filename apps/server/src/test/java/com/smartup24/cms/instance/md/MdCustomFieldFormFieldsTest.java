package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.field.FieldType;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository.CustomFieldRecord;
import com.smartup24.cms.instance.md.service.MdCustomFieldFormFields;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MdCustomFieldFormFieldsTest {

    @Test
    void customFieldsBecomeFormFieldsWithTheirRules() {
        MdCustomFieldService service = mock(MdCustomFieldService.class);
        when(service.getFields("NOTE"))
                .thenReturn(List.of(
                        record("topic", "Тема", "string", true, null),
                        record("stage", "Этап", "select", false, "[\"a\",\"b\"]"),
                        record("owner_id", "Ответственный", "user_ref", false, null),
                        record("due_at", "Срок", "datetime", false, null),
                        record("call_time", "Время звонка", "time", false, null),
                        record("blob", "Нечто", "json", false, null)));
        when(service.parseSelectOptions("[\"a\",\"b\"]")).thenReturn(List.of("a", "b"));

        List<FormField> fields = new MdCustomFieldFormFields(service).extraFields(MsNoteEntity.DEFINITION);

        assertThat(fields)
                .extracting(FormField::key)
                .containsExactly("cfTopic", "cfStage", "cfOwnerId", "cfDueAt", "cfCallTime");
        FormField topic = fields.getFirst();
        assertThat(topic.required()).isTrue();
        assertThat(topic.maxLength()).isEqualTo(MdCustomFieldFormFields.MAX_TEXT);
        assertThat(topic.label()).isEqualTo("Тема");
        assertThat(topic.attribute()).isEqualTo("topic");
        assertThat(fields.get(1).options()).containsExactly("a", "b");
        assertThat(fields.get(2).type()).isEqualTo(FieldType.REF);
        assertThat(fields.get(2).ref().path()).isEqualTo("/iam/users");
        // Plan 10/10, item 5.0: a moment and a time of day.
        assertThat(fields.get(3).type()).isEqualTo(FieldType.DATETIME);
        assertThat(fields.get(4).type()).isEqualTo(FieldType.TIME);
    }

    static CustomFieldRecord record(String code, String name, String type, boolean required, String options) {
        return new CustomFieldRecord(1L, "NOTE", code, name, type, required, null, options, 0, Instant.EPOCH, 1L);
    }
}
