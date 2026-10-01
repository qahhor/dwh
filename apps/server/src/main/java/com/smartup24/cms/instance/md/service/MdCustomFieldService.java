package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdCustomFieldDtos.CustomFieldView;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
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

        // A field changes the data shape of every record of the entity: a schema-level operation
        // that must be in the audit log (FR-AUD-1).
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
     * The attributes as they are stored: checked against the definitions in md_custom_fields (422 per field), and
     * the scalar value of a string or select field written as a JSON string. Equality on those fields is JSON
     * containment of a string (plan 10/10, item 3.7), so a number or boolean stored as such would never be found.
     */
    public Map<String, Object> checkedAttributes(String entityType, Map<String, Object> attributes) {
        List<MdCustomFieldRepository.CustomFieldRecord> fieldDefs = getSelf().getFields(entityType);
        if (fieldDefs.isEmpty()) {
            return attributes;
        }
        validate(fieldDefs, attributes);
        return attributes == null ? null : MdCustomFieldValues.textValuesAsStrings(fieldDefs, attributes);
    }

    private void validate(List<MdCustomFieldRepository.CustomFieldRecord> fieldDefs, Map<String, Object> attributes) {
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
            return fieldError(field, "required", "error.md.field_custom_required", Map.of());
        }
        if (value == null) {
            return null;
        }
        return switch (field.fieldType().toLowerCase()) {
            case "string" ->
                value.toString().length() > 4000
                        ? fieldError(field, "too_long", "error.md.field_custom_too_long", Map.of("max", 4000))
                        : null;
            case "number" ->
                MdCustomFieldValues.isNumber(value)
                        ? null
                        : fieldError(field, "invalid_number", "error.md.field_custom_number", Map.of());
            case "boolean" ->
                MdCustomFieldValues.isBoolean(value)
                        ? null
                        : fieldError(field, "invalid_boolean", "error.md.field_custom_boolean", Map.of());
            case "date" ->
                MdCustomFieldValues.isDate(value)
                        ? null
                        : fieldError(field, "invalid_date", "error.md.field_custom_date", Map.of());
            case "select" -> selectError(field, value.toString());
            case "user_ref" -> userRefError(field, value);
            default -> null;
        };
    }

    /** The error of an attribute; its text names the field ({@code {name}}) with the extra {@code params}. */
    private static FieldErrorItem fieldError(
            MdCustomFieldRepository.CustomFieldRecord field, String code, String messageKey, Map<String, ?> params) {
        Map<String, Object> all = new HashMap<>(params);
        all.put("name", field.name());
        return FieldErrorItem.keyed("attributes." + field.code(), code, messageKey, all);
    }

    private FieldErrorItem selectError(MdCustomFieldRepository.CustomFieldRecord field, String value) {
        List<String> allowedOptions = parseSelectOptions(field.optionsJson());
        if (allowedOptions.isEmpty() || allowedOptions.contains(value)) {
            return null;
        }
        return fieldError(
                field,
                "invalid_option",
                "error.md.field_custom_option",
                Map.of("options", String.join(", ", allowedOptions)));
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
                log.debug("User reference of {} is not a number: {}", field.code(), e.getMessage());
                return fieldError(field, "invalid_user_ref", "error.md.field_custom_user_ref", Map.of());
            }
        }
        if (userRepository == null) {
            return null;
        }
        var userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty() || !"A".equals(userOpt.get().state())) {
            return fieldError(field, "user_not_found", "error.md.field_custom_user_not_found", Map.of("id", userId));
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
