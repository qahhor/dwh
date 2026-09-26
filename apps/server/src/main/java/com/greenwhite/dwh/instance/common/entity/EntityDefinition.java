package com.greenwhite.dwh.instance.common.entity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An entity declared once (ADR-0019, 2.1): its right, the actions on it and the right each needs, its list in
 * the field registry, the fields and layout of its form, and what the platform gives it. A module declares it as
 * a bean; {@link EntityRegistry} collects them and {@code GET /api/v1/form-meta/{code}} serves them.
 *
 * @param code         the entity's code ({@code ms.notes}); usually its list's code
 * @param form         the right's form ({@code notes}); viewing needs {@code form.view}
 * @param listCode     its list in the field registry, or null
 * @param customEntity the entity type of its custom fields ({@code NOTE}), or null
 * @param fields       the form's own fields, in order
 * @param layout       sections of the form; every field appears in exactly one
 * @param actions      what can be done, each with the action of the right it needs
 * @param capabilities what the platform provides for it
 */
public record EntityDefinition(String code, String form, String listCode, String customEntity, List<FormField> fields,
                               List<FormSection> layout, List<EntityAction> actions, Set<EntityCapability> capabilities) {

    /** An action on the entity and the action of the entity's right it needs ({@code create} → {@code notes.create}). */
    public record EntityAction(String code, String permission) {
        public EntityAction {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(permission, "permission");
        }
    }

    /** A titled group of fields on the form. */
    public record FormSection(String key, String labelKey, List<String> fields) {
        public FormSection {
            fields = List.copyOf(fields);
        }
    }

    public EntityDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(form, "form");
        fields = List.copyOf(fields);
        layout = List.copyOf(layout);
        actions = List.copyOf(actions);
        capabilities = Set.copyOf(capabilities);
        Set<String> keys = new HashSet<>();
        for (FormField field : fields) {
            if (!keys.add(field.key())) {
                throw new IllegalArgumentException("Entity " + code + ": duplicate field " + field.key());
            }
        }
        Set<String> placed = new HashSet<>();
        for (FormSection section : layout) {
            for (String key : section.fields()) {
                if (!keys.contains(key) || !placed.add(key)) {
                    throw new IllegalArgumentException("Entity " + code + ": section " + section.key()
                            + " places an unknown or repeated field " + key);
                }
            }
        }
        if (!placed.equals(keys)) {
            throw new IllegalArgumentException("Entity " + code + ": every field belongs to one section");
        }
        if (capabilities.contains(EntityCapability.CUSTOM_FIELDS) != (customEntity != null)) {
            throw new IllegalArgumentException("Entity " + code + ": custom fields need their entity type, and only then");
        }
    }

    public Map<String, FormField> fieldsByKey() {
        Map<String, FormField> byKey = new java.util.LinkedHashMap<>();
        fields.forEach(field -> byKey.put(field.key(), field));
        return byKey;
    }
}
