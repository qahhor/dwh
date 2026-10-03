package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class MdCustomFieldServiceTest {

    private final MdCustomFieldRepository customFieldRepository = Mockito.mock(MdCustomFieldRepository.class);
    private final MdCustomFieldService service =
            new MdCustomFieldService(customFieldRepository, Mockito.mock(AuditLogService.class), new ObjectMapper());

    @Test
    @DisplayName("Валидация динамических полей должна отклонять отсутствующие обязательные поля")
    void shouldRejectMissingRequiredField() {
        when(customFieldRepository.findByEntityType("USER"))
                .thenReturn(List.of(new MdCustomFieldRepository.CustomFieldRecord(
                        1L, "USER", "inn", "ИНН", "number", true, null, "[]", 0, Instant.now(), 1L)));

        assertThatThrownBy(() -> service.checkedAttributes("USER", Map.of()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.custom_field_attributes_invalid");
    }

    @Test
    @DisplayName("Валидация динамических полей должна успешно проходить при корректных типах")
    void shouldPassValidAttributes() {
        when(customFieldRepository.findByEntityType("USER"))
                .thenReturn(List.of(
                        new MdCustomFieldRepository.CustomFieldRecord(
                                1L, "USER", "inn", "ИНН", "number", true, null, "[]", 0, Instant.now(), 1L),
                        new MdCustomFieldRepository.CustomFieldRecord(
                                2L,
                                "USER",
                                "is_vip",
                                "VIP клиент",
                                "boolean",
                                false,
                                "false",
                                "[]",
                                1,
                                Instant.now(),
                                1L),
                        new MdCustomFieldRepository.CustomFieldRecord(
                                3L,
                                "USER",
                                "birth_date",
                                "Дата рождения",
                                "date",
                                false,
                                null,
                                "[]",
                                2,
                                Instant.now(),
                                1L)));

        assertThatCode(() -> service.checkedAttributes(
                        "USER", Map.of("inn", 123456789, "is_vip", true, "birth_date", "2026-08-29")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Валидация должна отклонять невалидный формат числа и даты")
    void shouldRejectInvalidDataFormats() {
        when(customFieldRepository.findByEntityType("TASK"))
                .thenReturn(List.of(
                        new MdCustomFieldRepository.CustomFieldRecord(
                                1L, "TASK", "deadline", "Срок", "date", false, null, "[]", 0, Instant.now(), 1L),
                        new MdCustomFieldRepository.CustomFieldRecord(
                                2L, "TASK", "cost", "Стоимость", "number", false, null, "[]", 1, Instant.now(), 1L)));

        assertThatThrownBy(() -> service.checkedAttributes("TASK", Map.of("deadline", "invalid-date")))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service.checkedAttributes("TASK", Map.of("cost", "not_a_number")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Валидация select должна проверять допустимость значения по options_json")
    void shouldValidateSelectOptions() {
        when(customFieldRepository.findByEntityType("TASK"))
                .thenReturn(List.of(new MdCustomFieldRepository.CustomFieldRecord(
                        1L,
                        "TASK",
                        "priority_level",
                        "Уровень",
                        "select",
                        false,
                        null,
                        "[\"low\", \"medium\", \"high\"]",
                        0,
                        Instant.now(),
                        1L)));

        assertThatCode(() -> service.checkedAttributes("TASK", Map.of("priority_level", "medium")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> service.checkedAttributes("TASK", Map.of("priority_level", "super_critical")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.custom_field_attributes_invalid");
    }

    @Test
    @DisplayName("Валидация string должна проверять ограничение длины")
    void shouldRejectTooLongString() {
        when(customFieldRepository.findByEntityType("NOTE"))
                .thenReturn(List.of(new MdCustomFieldRepository.CustomFieldRecord(
                        1L, "NOTE", "memo", "Заметка", "string", false, null, "[]", 0, Instant.now(), 1L)));

        String longStr = "x".repeat(4001);
        assertThatThrownBy(() -> service.checkedAttributes("NOTE", Map.of("memo", longStr)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("3.7: a number or boolean of a string or select field is stored as a string, other types keep theirs")
    void textFieldValuesAreStoredAsStrings() {
        when(customFieldRepository.findByEntityType("TASK"))
                .thenReturn(List.of(
                        new MdCustomFieldRepository.CustomFieldRecord(
                                1L, "TASK", "contract", "Contract", "string", false, null, "[]", 0, Instant.now(), 1L),
                        new MdCustomFieldRepository.CustomFieldRecord(
                                2L, "TASK", "grade", "Grade", "select", false, null, "[1, 2, 3]", 1, Instant.now(), 1L),
                        new MdCustomFieldRepository.CustomFieldRecord(
                                3L, "TASK", "flag", "Flag", "string", false, null, "[]", 2, Instant.now(), 1L),
                        new MdCustomFieldRepository.CustomFieldRecord(
                                4L, "TASK", "cost", "Cost", "number", false, null, "[]", 3, Instant.now(), 1L)));

        Map<String, Object> stored = service.checkedAttributes(
                "TASK", Map.of("contract", 123, "grade", 2, "flag", true, "cost", 15, "other", 7));

        assertThat(stored)
                .containsEntry("contract", "123")
                .containsEntry("grade", "2")
                .containsEntry("flag", "true")
                .containsEntry("cost", 15)
                .containsEntry("other", 7);
        Map<String, Object> strings = Map.of("contract", "A-1");
        assertThat(service.checkedAttributes("TASK", strings)).isSameAs(strings);
    }

    @Test
    @DisplayName("Создание поля должно отклонять зарезервированные имена и некорректный slug")
    void shouldRejectInvalidOrReservedCode() {
        assertThatThrownBy(() -> service.createField("TASK", "id", "Идентификатор", "number", false, null, null, 0))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.custom_field_code_reserved")
                .hasFieldOrPropertyWithValue("params", Map.of("code", "id"));

        assertThatThrownBy(() ->
                        service.createField("TASK", "invalid code!", "Невалидный", "string", false, null, null, 0))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.custom_field_code_invalid");

        assertThatThrownBy(() ->
                        service.createField("TASK", "valid_code", "Валидный", "unknown_type", false, null, null, 0))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.custom_field_type_invalid")
                .hasFieldOrPropertyWithValue(
                        "params",
                        Map.of(
                                "type",
                                "unknown_type",
                                "allowed",
                                "boolean, date, datetime, number, select, string, time, user_ref"));
    }
}
