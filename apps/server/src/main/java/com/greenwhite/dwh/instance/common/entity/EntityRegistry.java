package com.greenwhite.dwh.instance.common.entity;

import com.greenwhite.dwh.instance.common.entity.EntityDefinition.FormSection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every declared entity by code (ADR-0019, 2.1). An entity is handed out with its custom fields as they are now:
 * the extenders' fields follow the declared ones in a section of their own, and a custom field whose key a
 * declared field already uses is left out.
 */
@Component
public class EntityRegistry {

    /** The section the custom fields go to. */
    public static final String CUSTOM_SECTION = "custom";

    private final Map<String, EntityDefinition> entities = new TreeMap<>();
    private final List<FormFieldExtender> extenders;

    @Autowired
    public EntityRegistry(List<EntityDefinition> declared, List<FormFieldExtender> extenders) {
        for (EntityDefinition entity : declared) {
            if (entities.put(entity.code(), entity) != null) {
                throw new IllegalStateException("Duplicate entity " + entity.code());
            }
        }
        this.extenders = List.copyOf(extenders);
    }

    public EntityRegistry(List<EntityDefinition> declared) {
        this(declared, List.of());
    }

    public Optional<EntityDefinition> find(String code) {
        return Optional.ofNullable(entities.get(code)).map(this::resolve);
    }

    public List<EntityDefinition> all() {
        return List.copyOf(entities.values());
    }

    /** The entity with its custom fields added in the {@value #CUSTOM_SECTION} section. */
    public EntityDefinition resolve(EntityDefinition entity) {
        if (extenders.isEmpty() || !entity.capabilities().contains(EntityCapability.CUSTOM_FIELDS)) return entity;
        Set<String> taken = new HashSet<>(entity.fieldsByKey().keySet());
        List<FormField> extra = extenders.stream()
                .flatMap(extender -> extender.extraFields(entity).stream())
                .filter(field -> taken.add(field.key()))
                .toList();
        if (extra.isEmpty()) return entity;
        List<FormField> fields = new ArrayList<>(entity.fields());
        fields.addAll(extra);
        List<FormSection> layout = new ArrayList<>(entity.layout());
        layout.add(new FormSection(CUSTOM_SECTION, "entity.section.custom", extra.stream().map(FormField::key).toList()));
        return new EntityDefinition(entity.code(), entity.form(), entity.listCode(), entity.customEntity(), fields, layout,
                entity.actions(), entity.capabilities());
    }
}
