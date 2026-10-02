package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Steps 1–2 of ADR-0032, 6.3, before any transaction: the entity exists with a table, its module is switched on and the
 * viewer holds its {@code view} right — otherwise the same 404 as an unknown code, so the answer tells nothing of the
 * entities that exist (ADR-0013, ADR-0028) — and the right of the operation, 403 without it.
 */
@Component
public class EntityGate {

    private final EntityRegistry registry;
    private final @Nullable InstalledModules modules;

    @Autowired
    public EntityGate(EntityRegistry registry, ObjectProvider<InstalledModules> modules) {
        this(registry, modules.getIfAvailable());
    }

    public EntityGate(EntityRegistry registry, @Nullable InstalledModules modules) {
        this.registry = registry;
        this.modules = modules;
    }

    /** The entity with its custom fields, when the viewer may see it (step 1). */
    public EntityDefinition viewable(String code) {
        return findViewable(code).orElseThrow(EntityGate::unknownEntity);
    }

    /** The entity with a table, when its module is on and the viewer holds its {@code view} right; else empty. */
    public Optional<EntityDefinition> findViewable(String code) {
        return registry.find(code)
                .filter(entity -> entity.model() != null)
                .filter(this::moduleActive)
                .filter(entity -> SecurityContext.hasPermission(entity.form(), "view"));
    }

    /**
     * The entity, when the viewer may see it, declares the action ({@code create}, {@code update}, {@code delete},
     * {@code archive} or its own) and the viewer holds the action's right (steps 1–2): an action the entity does not
     * declare answers as a missing one, a right the viewer lacks 403.
     */
    public EntityDefinition allowed(String code, String action) {
        EntityDefinition entity = viewable(code);
        EntityAction declared = entity.action(action)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCode.NOT_FOUND, "error.common.entity_action_not_found", Map.of("action", action)));
        require(entity, declared.permission());
        return entity;
    }

    private static void require(EntityDefinition entity, String permission) {
        if (!SecurityContext.hasPermission(entity.form(), permission)) {
            throw ApiException.permissionDenied(entity.form(), permission);
        }
    }

    private boolean moduleActive(EntityDefinition entity) {
        EntityDefinition.EntityMenu menu = entity.menu();
        String module = menu == null ? null : menu.module();
        InstalledModules installed = modules;
        return module == null || installed == null || installed.active(module);
    }

    static ApiException unknownEntity() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.entity_not_found");
    }
}
