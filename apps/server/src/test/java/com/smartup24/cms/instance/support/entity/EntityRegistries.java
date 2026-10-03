package com.smartup24.cms.instance.support.entity;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.hook.EntityActionCall;
import com.smartup24.cms.platform.api.entity.hook.EntityActionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Registries of declarations for tests that read what an entity declares — its rights, forms and lists — and never run
 * a record action: each declared action other than the standard ones gets a handler that refuses to run, as the
 * registry requires a handler for every declared action (ADR-0032, 6.7).
 */
public final class EntityRegistries {

    private static final Set<String> STANDARD =
            Set.of("create", "update", EntityDefinition.ARCHIVE, EntityDefinition.DELETE);

    private EntityRegistries() {}

    /** The registry of {@code entities} without records, hooks or a runtime. */
    public static EntityRegistry declarations(List<EntityDefinition> entities) {
        List<EntityActionHandler> handlers = new ArrayList<>();
        for (EntityDefinition entity : entities) {
            entity.actions().stream()
                    .map(EntityDefinition.EntityAction::code)
                    .filter(code -> !STANDARD.contains(code))
                    .forEach(code -> handlers.add(refusing(entity.code(), code)));
        }
        return new EntityRegistry(entities, List.of(), List.of(), List.of(), handlers, () -> {
            throw new IllegalStateException("A registry of declarations has no runtime");
        });
    }

    private static EntityActionHandler refusing(String entity, String action) {
        return new EntityActionHandler() {
            @Override
            public String entity() {
                return entity;
            }

            @Override
            public String action() {
                return action;
            }

            @Override
            public void run(EntityActionCall call) {
                throw new IllegalStateException("A registry of declarations runs no action");
            }
        };
    }
}
