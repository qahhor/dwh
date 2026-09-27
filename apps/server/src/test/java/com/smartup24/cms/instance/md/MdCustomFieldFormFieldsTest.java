package com.smartup24.cms.instance.md;

import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.FormFieldType;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository.CustomFieldRecord;
import com.smartup24.cms.instance.md.service.MdCustomFieldFormFields;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MdCustomFieldFormFieldsTest {

    @Test
    void customFieldsBecomeFormFieldsWithTheirRules() {
        MdCustomFieldRepository repository = mock(MdCustomFieldRepository.class);
        MdCustomFieldService service = mock(MdCustomFieldService.class);
        when(repository.findByEntityType("NOTE")).thenReturn(List.of(
                record("topic", "Тема", "string", true, null),
                record("stage", "Этап", "select", false, "[\"a\",\"b\"]"),
                record("owner_id", "Ответственный", "user_ref", false, null),
                record("blob", "Нечто", "json", false, null)));
        when(service.parseSelectOptions("[\"a\",\"b\"]")).thenReturn(List.of("a", "b"));

        List<FormField> fields = new MdCustomFieldFormFields(repository, service).extraFields(MsNoteEntity.DEFINITION);

        assertThat(fields).extracting(FormField::key).containsExactly("cfTopic", "cfStage", "cfOwnerId");
        FormField topic = fields.getFirst();
        assertThat(topic.required()).isTrue();
        assertThat(topic.maxLength()).isEqualTo(MdCustomFieldFormFields.MAX_TEXT);
        assertThat(topic.label()).isEqualTo("Тема");
        assertThat(topic.attribute()).isEqualTo("topic");
        assertThat(fields.get(1).options()).containsExactly("a", "b");
        assertThat(fields.get(2).type()).isEqualTo(FormFieldType.REF);
        assertThat(fields.get(2).ref().path()).isEqualTo("/iam/users");
    }

    private static CustomFieldRecord record(String code, String name, String type, boolean required, String options) {
        return new CustomFieldRecord(1L, "NOTE", code, name, type, required, null, options, 0, Instant.EPOCH);
    }
}
