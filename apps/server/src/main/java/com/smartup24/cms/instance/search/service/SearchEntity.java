package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.search.EntitySearchSpec;
import java.util.List;
import java.util.Objects;

/**
 * An entity the global search finds (ADR-0032, 10.3; plan 10/10, item 5.8): its declaration and its search spec. The
 * indexed type of its records is its code, its collection in a generation is named after it, its documents hold the
 * fields the spec names under their keys.
 *
 * @param definition the entity's declaration, with a table and the SEARCH capability
 */
public record SearchEntity(EntityDefinition definition) {

    /** The prefix of an entity's collection name within a generation's prefix (ADR-0032, 10.3). */
    public static final String COLLECTION_PREFIX = "entity_";

    public SearchEntity {
        Objects.requireNonNull(definition, "definition");
        if (definition.model() == null || definition.model().search() == null) {
            throw new IllegalArgumentException("Entity " + definition.code() + " declares no search");
        }
    }

    /** The entity's code: the indexed type of its records. */
    public String code() {
        return definition.code();
    }

    public EntityModel model() {
        return Objects.requireNonNull(definition.model(), definition.code());
    }

    public EntitySearchSpec spec() {
        return Objects.requireNonNull(model().search(), definition.code());
    }

    /** The searched fields, the title first. */
    public List<EntityField> fields() {
        return spec().fields().stream()
                .map(key -> model().field(key).orElseThrow())
                .toList();
    }

    /** The field a hit is named by. */
    public EntityField titleField() {
        return fields().getFirst();
    }

    /** Whether the field holds natural language: a stemmed field of the Russian profile; an address is no language. */
    public static boolean naturalLanguage(EntityField field) {
        return field.type() != FieldType.EMAIL && field.type() != FieldType.PHONE && field.type() != FieldType.URL;
    }

    /** The entity's collection within a generation whose collections start with {@code prefix}. */
    public String collection(String prefix) {
        return prefix + COLLECTION_PREFIX + code().replace('.', '_');
    }

    /** Whether archived records leave the index (ADR-0032, 5.4). */
    public boolean archivable() {
        return definition.capabilities().contains(EntityCapability.ARCHIVE);
    }

    /** Where a hit leads in the web application. */
    public String targetUrl(long id) {
        return spec().targetUrl(code(), id);
    }
}
