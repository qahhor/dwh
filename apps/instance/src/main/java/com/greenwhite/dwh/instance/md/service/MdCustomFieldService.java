package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.repository.MdCustomFieldRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MdCustomFieldService {

    private final MdCustomFieldRepository customFieldRepository;

    public MdCustomFieldService(MdCustomFieldRepository customFieldRepository) {
        this.customFieldRepository = customFieldRepository;
    }

    @Transactional(readOnly = true)
    public List<MdCustomFieldRepository.CustomFieldRecord> getFields(String entityType) {
        return customFieldRepository.findByEntityType(entityType);
    }

    @Transactional
    public MdCustomFieldRepository.CustomFieldRecord createField(
            String entityType, String code, String name, String fieldType,
            boolean isRequired, String defaultValue, Object options, int orderNo) {

        if (customFieldRepository.findByCode(entityType, code).isPresent()) {
            throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "Поле с таким кодом уже существует для сущности " + entityType);
        }

        return customFieldRepository.create(entityType, code, name, fieldType, isRequired, defaultValue, options, orderNo);
    }

    @Transactional
    public void updateField(Long id, String name, Boolean isRequired, String defaultValue, Object options, Integer orderNo) {
        customFieldRepository.update(id, name, isRequired, defaultValue, options, orderNo);
    }

    @Transactional
    public void deleteField(Long id) {
        customFieldRepository.delete(id);
    }

    /**
     * Dynamic Attribute Validation against schema definitions in md_custom_fields.
     */
    public void validateAttributes(String entityType, Map<String, Object> attributes) {
        List<MdCustomFieldRepository.CustomFieldRecord> fieldDefs = customFieldRepository.findByEntityType(entityType);
        if (fieldDefs.isEmpty()) {
            return;
        }

        Map<String, Object> safeAttrs = attributes != null ? attributes : Map.of();
        List<FieldErrorItem> errors = new ArrayList<>();

        for (var field : fieldDefs) {
            Object value = safeAttrs.get(field.code());

            if (field.isRequired() && (value == null || value.toString().trim().isEmpty())) {
                errors.add(new FieldErrorItem("attributes." + field.code(), "required", "Поле " + field.name() + " обязательно для заполнения"));
                continue;
            }

            if (value != null) {
                switch (field.fieldType().toLowerCase()) {
                    case "number" -> {
                        if (!(value instanceof Number)) {
                            try {
                                Double.parseDouble(value.toString());
                            } catch (NumberFormatException e) {
                                errors.add(new FieldErrorItem("attributes." + field.code(), "invalid_number", "Поле " + field.name() + " должно быть числом"));
                            }
                        }
                    }
                    case "boolean" -> {
                        if (!(value instanceof Boolean) && !value.toString().equalsIgnoreCase("true") && !value.toString().equalsIgnoreCase("false")) {
                            errors.add(new FieldErrorItem("attributes." + field.code(), "invalid_boolean", "Поле " + field.name() + " должно быть булевым"));
                        }
                    }
                    case "date" -> {
                        try {
                            LocalDate.parse(value.toString());
                        } catch (DateTimeParseException e) {
                            errors.add(new FieldErrorItem("attributes." + field.code(), "invalid_date", "Поле " + field.name() + " должно содержать корректную дату"));
                        }
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            throw ApiException.validation("Ошибка валидации динамических атрибутов", errors);
        }
    }
}
