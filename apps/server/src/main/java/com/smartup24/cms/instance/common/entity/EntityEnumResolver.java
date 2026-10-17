package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldResolver;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Gives the enumerations of the entities' lists their items as they are now (ADR-0032, 4.5): the values a filter
 * takes and the words the list shows come from the reference entity, read through {@link EntityEnums}. A list field of
 * another list, or one that is no enumeration of a reference, is left as it is. The enumerations of a live entity — a
 * reference list an administrator keeps (ADR-0041) — are found in the live catalog of the request.
 */
@Component
public class EntityEnumResolver implements QueryFieldResolver {

    /** The reference entity of each enumeration, by list code and field key. */
    private final Map<String, Map<String, String>> references = new HashMap<>();

    private final EntityEnums enums;
    private final Supplier<LiveEntities> live;

    @Autowired
    public EntityEnumResolver(List<EntityDefinition> entities, EntityEnums enums, ObjectProvider<LiveEntities> live) {
        this(entities, enums, () -> live.getIfAvailable(LiveEntities::empty));
    }

    public EntityEnumResolver(List<EntityDefinition> entities, EntityEnums enums) {
        this(entities, enums, LiveEntities::empty);
    }

    private EntityEnumResolver(List<EntityDefinition> entities, EntityEnums enums, Supplier<LiveEntities> live) {
        this.enums = enums;
        this.live = live;
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
        if (reference == null) reference = liveReference(list.code(), field.key());
        return reference == null ? field : field.withEnumeration(enums.items(reference));
    }

    private @Nullable String liveReference(String listCode, String key) {
        for (EntityDefinition entity : live.get().all()) {
            EntityModel model = entity.model();
            if (model == null || !listCode.equals(Objects.requireNonNullElse(entity.listCode(), entity.code())))
                continue;
            return model.fields().stream()
                    .filter(field -> field.key().equals(key))
                    .map(field -> field.options().enumeration())
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }
}
