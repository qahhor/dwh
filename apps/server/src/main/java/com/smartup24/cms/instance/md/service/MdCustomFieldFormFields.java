package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.FormFieldExtender;
import com.smartup24.cms.instance.common.entity.FormFieldType;
import com.smartup24.cms.instance.common.query.QueryRef;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository.CustomFieldRecord;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The custom fields of an entity as fields of its form (ADR-0019, 2.3), with the same key as in its list
 * ({@link MdCustomFieldQueryFields#key}) and the rules the custom field service checks on save: required, text up
 * to 4000 characters, the options of a select, a user for a user reference.
 */
@Component
public class MdCustomFieldFormFields implements FormFieldExtender {

    /** The longest text a custom field takes (MdCustomFieldService). */
    public static final int MAX_TEXT = 4000;

    private final MdCustomFieldRepository repository;
    private final MdCustomFieldService service;

    public MdCustomFieldFormFields(MdCustomFieldRepository repository, MdCustomFieldService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public List<FormField> extraFields(EntityDefinition entity) {
        if (entity.customEntity() == null) return List.of();
        return repository.findByEntityType(entity.customEntity()).stream()
                .map(this::toField)
                .filter(Objects::nonNull)
                .toList();
    }

    private FormField toField(CustomFieldRecord record) {
        String key = MdCustomFieldQueryFields.key(record.code());
        if (key.length() > 64) return null;
        FormField field = switch (record.fieldType().toLowerCase(Locale.ROOT)) {
            case "string" -> FormField.of(key, "", FormFieldType.TEXT).length(null, MAX_TEXT);
            case "number" -> FormField.of(key, "", FormFieldType.NUMBER);
            case "date" -> FormField.of(key, "", FormFieldType.DATE);
            case "boolean" -> FormField.of(key, "", FormFieldType.BOOLEAN);
            case "select" -> {
                List<String> options = service.parseSelectOptions(record.optionsJson());
                yield options.isEmpty()
                        ? FormField.of(key, "", FormFieldType.TEXT).length(null, MAX_TEXT)
                        : FormField.select(key, "", options, null);
            }
            case "user_ref" ->
                FormField.of(key, "", FormFieldType.NUMBER).refersTo(QueryRef.paged("/iam/users", "name"));
            default -> null;
        };
        if (field == null) return null;
        FormField named = field.custom(record.name(), record.code());
        return record.isRequired() ? named.asRequired() : named;
    }
}
