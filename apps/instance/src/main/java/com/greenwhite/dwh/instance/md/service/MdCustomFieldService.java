package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
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
    private final AuditLogService auditLogService;

    public MdCustomFieldService(MdCustomFieldRepository customFieldRepository,
                                AuditLogService auditLogService) {
        this.customFieldRepository = customFieldRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<MdCustomFieldRepository.CustomFieldRecord> getFields(String entityType) {
        if (entityType == null || entityType.isBlank() || entityType.equalsIgnoreCase("ALL")) {
            return customFieldRepository.findAll();
        }
        return customFieldRepository.findByEntityType(entityType);
    }

    @Transactional
    public MdCustomFieldRepository.CustomFieldRecord createField(
            String entityType, String code, String name, String fieldType,
            boolean isRequired, String defaultValue, Object options, int orderNo) {

        if (customFieldRepository.findByCode(entityType, code).isPresent()) {
            throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "Поле с таким кодом уже существует для сущности " + entityType);
        }

        var field = customFieldRepository.create(entityType, code, name, fieldType, isRequired, defaultValue, options, orderNo);

        // Поле меняет форму данных всех записей сущности — операция уровня схемы,
        // и она обязана быть в журнале (FR-AUD-1).
        auditLogService.logChange("md_custom_fields", String.valueOf(field.id()), "I",
                List.of("entity_type", "code", "name", "field_type", "is_required"),
                null,
                Map.of("entity_type", entityType, "code", code, "name", name,
                        "field_type", fieldType, "is_required", isRequired));

        return field;
    }

    @Transactional
    public void updateField(Long id, String name, Boolean isRequired, String defaultValue, Object options, Integer orderNo) {
        var before = requireField(id);
        customFieldRepository.update(id, name, isRequired, defaultValue, options, orderNo);

        auditLogService.logChange("md_custom_fields", String.valueOf(id), "U",
                List.of("name", "is_required", "default_value", "order_no"),
                Map.of("name", before.name(), "is_required", before.isRequired()),
                Map.of("name", name != null ? name : before.name(),
                        "is_required", isRequired != null ? isRequired : before.isRequired()));
    }

    @Transactional
    public void deleteField(Long id) {
        var before = requireField(id);
        customFieldRepository.delete(id);

        auditLogService.logChange("md_custom_fields", String.valueOf(id), "D",
                List.of("entity_type", "code", "name"),
                Map.of("entity_type", before.entityType(), "code", before.code(), "name", before.name()),
                null);
    }

    private MdCustomFieldRepository.CustomFieldRecord requireField(Long id) {
        return customFieldRepository.findById(id).orElseThrow(() ->
                ApiException.notFound(ErrorCode.NOT_FOUND, "Динамическое поле не найдено"));
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
