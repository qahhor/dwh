package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.hook.EntityActionHandler;
import com.smartup24.cms.instance.common.entity.hook.EntityHooks;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What {@link EntityRegistry} refuses at the start (ADR-0032, 6.5–6.7): hooks or an action handler of an entity that is
 * not declared with a table, a second bean of the same, a handler of an action the entity does not declare or of a
 * standard one, a declared action without its handler, and a reference whose target entity is not declared with a
 * table.
 */
final class EntityRegistryChecks {

    /** The actions the runtime performs itself; any other declared action needs a handler. */
    static final Set<String> STANDARD = Set.of("create", "update", EntityDefinition.ARCHIVE, EntityDefinition.DELETE);

    private EntityRegistryChecks() {}

    /** A reference names its target by an entity's code: that entity has a table the runtime reads. */
    static void references(Map<String, EntityDefinition> entities) {
        for (EntityDefinition entity : entities.values()) {
            EntityModel model = entity.model();
            if (model == null) continue;
            for (EntityField field : model.fields()) {
                String target = field.options().target();
                if (target == null) continue;
                EntityDefinition named = entities.get(target);
                if (named == null || named.model() == null) {
                    throw new IllegalStateException("Entity " + entity.code() + ": the field " + field.key()
                            + " refers to " + target + ", which is no entity with a table");
                }
            }
        }
    }

    /** The hooks by entity code: one bean per entity, only of an entity declared with a table. */
    static Map<String, EntityHooks> hooks(Map<String, EntityDefinition> entities, List<EntityHooks> hooks) {
        Map<String, EntityHooks> byEntity = new TreeMap<>();
        for (EntityHooks one : hooks) {
            EntityDefinition entity = entities.get(one.entity());
            if (entity == null || entity.model() == null) {
                throw new IllegalStateException("Hooks of an entity without a table: " + one.entity());
            }
            if (byEntity.put(one.entity(), one) != null) {
                throw new IllegalStateException("Duplicate hooks of entity " + one.entity());
            }
        }
        return byEntity;
    }

    /** The handlers by entity and action: one per declared action of an entity with a table, and no other. */
    static Map<String, EntityActionHandler> handlers(
            Map<String, EntityDefinition> entities, List<EntityActionHandler> handlers) {
        Map<String, EntityActionHandler> byAction = new TreeMap<>();
        for (EntityActionHandler handler : handlers) {
            EntityDefinition entity = entities.get(handler.entity());
            if (entity == null
                    || entity.model() == null
                    || STANDARD.contains(handler.action())
                    || entity.action(handler.action()).isEmpty()) {
                throw new IllegalStateException(
                        "A handler of an action " + handler.entity() + "/" + handler.action() + " not declared");
            }
            if (byAction.put(handlerKey(handler.entity(), handler.action()), handler) != null) {
                throw new IllegalStateException(
                        "Duplicate handler of action " + handler.entity() + "/" + handler.action());
            }
        }
        for (EntityDefinition entity : entities.values()) {
            if (entity.model() == null) continue;
            for (EntityAction action : entity.actions()) {
                if (!STANDARD.contains(action.code())
                        && !byAction.containsKey(handlerKey(entity.code(), action.code()))) {
                    throw new IllegalStateException("Entity " + entity.code() + " declares the action " + action.code()
                            + " without its EntityActionHandler (ADR-0032, 6.7)");
                }
            }
        }
        return byAction;
    }

    static String handlerKey(String entity, String action) {
        return entity + "/" + action;
    }
}
