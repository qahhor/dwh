package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdCustomFieldDtos.CustomFieldView;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MdCustomFieldService {

    private static final Logger log = LoggerFactory.getLogger(MdCustomFieldService.class);

    private static final Set<String> RESERVED_CODES = Set.of(
            "id",
            "code",
            "name",
            "title",
            "state",
            "status",
            "created_at",
            "modified_at",
            "created_by",
            "modified_by",
            "login",
            "email",
            "password",
            "task_type",
            "priority",
            "description",
            "attributes",
            "options",
            "values");

    private static final Set<String> VALID_FIELD_TYPES =
            Set.of("string", "number", "boolean", "date", "select", "user_ref");

    private final MdCustomFieldRepository customFieldRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final MdUserRepository userRepository;
    private final ObjectProvider<MdCustomFieldService> selfProvider;

    @Autowired
    public MdCustomFieldService(
            MdCustomFieldRepository customFieldRepository,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            MdUserRepository userRepository,
            @Lazy ObjectProvider<MdCustomFieldService> selfProvider) {
        this.customFieldRepository = customFieldRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper != null ? objectMapper : JsonMapper.shared();
        this.userRepository = userRepository;
        this.selfProvider = selfProvider;
    }

    public MdCustomFieldService(
            MdCustomFieldRepository customFieldRepository,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            MdUserRepository userRepository) {
        this(customFieldRepository, auditLogService, objectMapper, userRepository, null);
    }

    public MdCustomFieldService(MdCustomFieldRepository customFieldRepository, AuditLogService auditLogService) {
        this(customFieldRepository, auditLogService, JsonMapper.shared(), null, null);
    }

    private MdCustomFieldService getSelf() {
        return selfProvider != null && selfProvider.getIfAvailable() != null ? selfProvider.getIfAvailable() : this;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "customFields", key = "#entityType != null ? #entityType.toLowerCase() : 'all'")
    public List<MdCustomFieldRepository.CustomFieldRecord> getFields(String entityType) {
        if (entityType == null || entityType.isBlank() || entityType.equalsIgnoreCase("ALL")) {
            return customFieldRepository.findAll();
        }
        return customFieldRepository.findByEntityType(entityType);
    }

    /** The definitions as the settings screen reads them; the cached list stays the source. */
    @Transactional(readOnly = true)
    public List<CustomFieldView> listFields(String entityType) {
        return getSelf().getFields(entityType).stream()
                .map(MdCustomFieldService::view)
                .toList();
    }

    private static CustomFieldView view(MdCustomFieldRepository.CustomFieldRecord field) {
        return new CustomFieldView(
                field.id(),
                field.entityType(),
                field.code(),
                field.name(),
                field.fieldType(),
                field.isRequired(),
                field.defaultValue(),
                field.optionsJson(),
                field.orderNo(),
                field.createdAt(),
                field.revision());
    }

    @Transactional
    @CacheEvict(value = "customFields", allEntries = true)
    public CustomFieldView createField(
            String entityType,
            String code,
            String name,
            String fieldType,
            boolean isRequired,
            String defaultValue,
            Object options,
            int orderNo) {

        if (entityType == null || !entityType.trim().toUpperCase().matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.md.custom_field_entity_type_invalid");
        }

        String normalizedCode = (code != null ? code.trim().toLowerCase() : "");
        if (!normalizedCode.matches("^[a-z][a-z0-9_]{1,63}$")) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.md.custom_field_code_invalid");
        }

        if (RESERVED_CODES.contains(normalizedCode)) {
            throw ApiException.badRequest(
                    ErrorCode.BAD_REQUEST, "error.md.custom_field_code_reserved", Map.of("code", normalizedCode));
        }

        String normalizedFieldType = (fieldType != null ? fieldType.trim().toLowerCase() : "");
        if (!VALID_FIELD_TYPES.contains(normalizedFieldType)) {
            throw ApiException.badRequest(
                    ErrorCode.BAD_REQUEST,
                    "error.md.custom_field_type_invalid",
                    Map.of(
                            "type",
                            String.valueOf(fieldType),
                            "allowed",
                            String.join(", ", new TreeSet<>(VALID_FIELD_TYPES))));
        }

        if (customFieldRepository.findByCode(entityType, normalizedCode).isPresent()) {
            throw ApiException.conflict(
                    ErrorCode.CODE_ALREADY_EXISTS, "error.md.custom_field_exists", Map.of("entity", entityType));
        }

        var field = customFieldRepository.create(
                entityType, normalizedCode, name, normalizedFieldType, isRequired, defaultValue, options, orderNo);

        // Поле меняет форму данных всех записей сущности — операция уровня схемы,
        // и она обязана быть в журнале (FR-AUD-1).
        auditLogService.logChange(
                "md_custom_fields",
                String.valueOf(field.id()),
                "I",
                List.of("entity_type", "code", "name", "field_type", "is_required"),
                null,
                Map.of(
                        "entity_type",
                        entityType,
                        "code",
                        normalizedCode,
                        "name",
                        name,
                        "field_type",
                        normalizedFieldType,
                        "is_required",
                        isRequired));

        return view(field);
    }

    @Transactional
    @CacheEvict(value = "customFields", allEntries = true)
    public long updateField(
            Long id,
            String name,
            Boolean isRequired,
            String defaultValue,
            Object options,
            Integer orderNo,
            long expectedRevision) {
        var before = requireField(id);
        long revision =
                customFieldRepository.update(id, name, isRequired, defaultValue, options, orderNo, expectedRevision);
        var after = requireField(id);

        // The stored rows, before and after: the default and the options change what users may enter, so they
        // are audited too (they were left out before, roadmap item 52).
        auditLogService.logChange(
                "md_custom_fields",
                String.valueOf(id),
                "U",
                List.of("name", "is_required", "default_value", "options_json", "order_no"),
                auditState(before),
                auditState(after));
        return revision;
    }

    private static Map<String, Object> auditState(MdCustomFieldRepository.CustomFieldRecord field) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", field.name());
        state.put("is_required", field.isRequired());
        state.put("default_value", field.defaultValue());
        state.put("options_json", field.optionsJson());
        state.put("order_no", field.orderNo());
        return state;
    }

    @Transactional
    @CacheEvict(value = "customFields", allEntries = true)
    public void deleteField(Long id) {
        var before = requireField(id);
        customFieldRepository.delete(id);

        auditLogService.logChange(
                "md_custom_fields",
                String.valueOf(id),
                "D",
                List.of("entity_type", "code", "name"),
                Map.of("entity_type", before.entityType(), "code", before.code(), "name", before.name()),
                null);
    }

    private MdCustomFieldRepository.CustomFieldRecord requireField(Long id) {
        return customFieldRepository
                .findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.custom_field_not_found"));
    }

    /**
     * Dynamic Attribute Validation against schema definitions in md_custom_fields.
     */
    public void validateAttributes(String entityType, Map<String, Object> attributes) {
        List<MdCustomFieldRepository.CustomFieldRecord> fieldDefs = getSelf().getFields(entityType);
        if (fieldDefs.isEmpty()) {
            return;
        }

        Map<String, Object> safeAttrs = attributes != null ? attributes : Map.of();
        List<FieldErrorItem> errors = new ArrayList<>();

        for (var field : fieldDefs) {
            FieldErrorItem error = attributeError(field, safeAttrs.get(field.code()));
            if (error != null) {
                errors.add(error);
            }
        }

        if (!errors.isEmpty()) {
            throw ApiException.validation("error.md.custom_field_attributes_invalid", errors);
        }
    }

    /** The one problem of an attribute value against its definition, or null when the value fits. */
    private FieldErrorItem attributeError(MdCustomFieldRepository.CustomFieldRecord field, Object value) {
        if (field.isRequired() && (value == null || value.toString().trim().isEmpty())) {
            return fieldError(field, "required", "Поле " + field.name() + " обязательно для заполнения");
        }
        if (value == null) {
            return null;
        }
        return switch (field.fieldType().toLowerCase()) {
            case "string" ->
                value.toString().length() > 4000
                        ? fieldError(
                                field,
                                "too_long",
                                "Значение поля " + field.name() + " не должно превышать 4000 символов")
                        : null;
            case "number" ->
                isNumber(value)
                        ? null
                        : fieldError(field, "invalid_number", "Поле " + field.name() + " должно быть числом");
            case "boolean" ->
                isBoolean(value)
                        ? null
                        : fieldError(field, "invalid_boolean", "Поле " + field.name() + " должно быть булевым");
            case "date" ->
                isDate(value)
                        ? null
                        : fieldError(
                                field, "invalid_date", "Поле " + field.name() + " должно содержать корректную дату");
            case "select" -> selectError(field, value.toString());
            case "user_ref" -> userRefError(field, value);
            default -> null;
        };
    }

    private static FieldErrorItem fieldError(
            MdCustomFieldRepository.CustomFieldRecord field, String code, String message) {
        return new FieldErrorItem("attributes." + field.code(), code, message);
    }

    private static boolean isNumber(Object value) {
        if (value instanceof Number) {
            return true;
        }
        try {
            Double.parseDouble(value.toString());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isBoolean(Object value) {
        return value instanceof Boolean
                || value.toString().equalsIgnoreCase("true")
                || value.toString().equalsIgnoreCase("false");
    }

    private static boolean isDate(Object value) {
        try {
            LocalDate.parse(value.toString());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private FieldErrorItem selectError(MdCustomFieldRepository.CustomFieldRecord field, String value) {
        List<String> allowedOptions = parseSelectOptions(field.optionsJson());
        if (allowedOptions.isEmpty() || allowedOptions.contains(value)) {
            return null;
        }
        return fieldError(
                field,
                "invalid_option",
                "Значение поля " + field.name() + " должно быть одним из вариантов: "
                        + String.join(", ", allowedOptions));
    }

    /** A reference is a numeric id of an active user; without a user repository only its form is checked. */
    private FieldErrorItem userRefError(MdCustomFieldRepository.CustomFieldRecord field, Object value) {
        Long userId;
        if (value instanceof Number n) {
            userId = n.longValue();
        } else {
            try {
                userId = Long.parseLong(value.toString().trim());
            } catch (NumberFormatException e) {
                return fieldError(
                        field,
                        "invalid_user_ref",
                        "Поле " + field.name() + " должно содержать числовой ID пользователя");
            }
        }
        if (userRepository == null) {
            return null;
        }
        var userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty() || !"A".equals(userOpt.get().state())) {
            return fieldError(field, "user_not_found", "Пользователь с ID " + userId + " не найден или неактивен");
        }
        return null;
    }

    public List<String> parseSelectOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(optionsJson);
            if (!root.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode node : root) {
                if (node.isTextual() || node.isNumber() || node.isBoolean()) {
                    result.add(node.asText());
                } else if (node.isObject() && node.has("value")) {
                    result.add(node.get("value").asText());
                }
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("custom_field_options_unreadable error={}", e.toString());
            return List.of();
        }
    }
}
