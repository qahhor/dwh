package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldResolver;
import com.smartup24.cms.instance.common.query.QueryList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Gives the enumerations of the entities' lists their items as they are now (ADR-0032, 4.5): the values a filter
 * takes and the words the list shows come from the reference entity, read through {@link EntityEnums}. A list field of
 * another list, or one that is no enumeration of a reference, is left as it is.
 */
@Component
public class EntityEnumResolver implements QueryFieldResolver {

    /** The reference entity of each enumeration, by list code and field key. */
    private final Map<String, Map<String, String>> references = new HashMap<>();

    private final EntityEnums enums;

    public EntityEnumResolver(List<EntityDefinition> entities, EntityEnums enums) {
        this.enums = enums;
        for (EntityDefinition entity : entities) {
            EntityModel model = entity.model();
            if (model == null) continue;
            String list = Objects.requireNonNull(entity.listCode(), entity.code());
            for (EntityField field : model.fields()) {
                String reference = field.options().enumeration();
                if (reference != null) {
                    references.computeIfAbsent(list, code -> new HashMap<>()).put(field.key(), reference);
                }
            }
        }
    }

    @Override
    public QueryField resolve(QueryList list, QueryField field) {
        if (!QueryField.ENUM_FORMAT.equals(field.format())) return field;
        String reference = references.getOrDefault(list.code(), Map.of()).get(field.key());
        return reference == null ? field : field.withEnumeration(enums.items(reference));
    }
}
