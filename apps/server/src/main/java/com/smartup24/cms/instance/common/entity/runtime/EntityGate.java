package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.module.ModuleManifest;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Steps 1–2 of ADR-0032, 6.3, before any transaction, and the one entry point of every path that serves an entity —
 * its records, bulk actions, files, form and list descriptions, history and export: the entity exists, its module is
 * switched on and the viewer holds its {@code view} right — otherwise the same 404 as an unknown code, so the answer
 * tells nothing of the entities that exist (ADR-0013, ADR-0028) — and the right of the operation, 403 without it.
 *
 * <p>The module of an entity comes from its declaration: the permission area of its form (ADR-0028), held by the
 * module whose manifest has that code or lists the area ({@link ModuleManifest#areas}). An entity whose area no
 * installed module holds refuses the start, so a switched-off module closes every entity it declares, a menu item or
 * none.
 */
@Component
public class EntityGate {

    private final EntityRegistry registry;
    private final @Nullable InstalledModules modules;
    private final Map<String, String> moduleOf;

    @Autowired
    public EntityGate(EntityRegistry registry, ObjectProvider<InstalledModules> modules, ModuleCatalog catalog) {
        this(registry, modules.getIfAvailable(), catalog);
    }

    /** Without {@code catalog} no entity has a module to switch off: a registry of declarations alone. */
    public EntityGate(EntityRegistry registry, @Nullable InstalledModules modules, @Nullable ModuleCatalog catalog) {
        this.registry = registry;
        this.modules = modules;
        this.moduleOf = catalog == null ? Map.of() : modules(registry.all(), catalog);
    }

    /** A gate of the declarations alone: every module counts as switched on. */
    public static EntityGate of(EntityRegistry registry) {
        return new EntityGate(registry, (InstalledModules) null, (ModuleCatalog) null);
    }

    /**
     * The installed module of every entity, by entity code: the module that holds the permission area of its form.
     * An entity whose area no module holds refuses the start, every such entity named.
     */
    static Map<String, String> modules(List<EntityDefinition> entities, ModuleCatalog catalog) {
        Map<String, String> byEntity = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (EntityDefinition entity : entities) {
            String area = area(entity.form());
            Optional<ModuleManifest> holder = catalog.holding(area);
            if (holder.isPresent()) {
                byEntity.put(entity.code(), holder.get().code());
            } else {
                problems.add(entity.code() + " (form " + entity.form() + ", area " + area + ")");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("No installed module holds the permission area of these entities"
                    + " (ADR-0028; ADR-0033, 6.2: the manifest's code or its \"areas\"): "
                    + String.join(", ", problems));
        }
        return Map.copyOf(byEntity);
    }

    private static String area(String form) {
        int dot = form.indexOf('.');
        return dot > 0 ? form.substring(0, dot) : form;
    }

    /** The installed module the entity belongs to, or empty without a module catalog. */
    public Optional<String> moduleOf(String code) {
        return Optional.ofNullable(moduleOf.get(code));
    }

    /** The entity with its custom fields, when the viewer may see it (step 1). */
    public EntityDefinition viewable(String code) {
        return findViewable(code).orElseThrow(EntityGate::unknownEntity);
    }

    /** The entity with a table, when its module is on and the viewer holds its {@code view} right; else empty. */
    public Optional<EntityDefinition> findViewable(String code) {
        return findVisible(code).filter(entity -> entity.model() != null);
    }

    /**
     * The entity, with a table or a module's own records, when its module is on and the viewer holds its {@code view}
     * right; else empty: what the bulk actions, the files, the form, the history and the export of an entity open with.
     */
    public Optional<EntityDefinition> findVisible(String code) {
        return registry.find(code)
                .filter(this::moduleActive)
                .filter(entity -> SecurityContext.hasPermission(entity.form(), "view"));
    }

    /** Whether the entity {@code code} is known and the viewer may see it: what a related list of a card needs. */
    public boolean visible(String code) {
        return findVisible(code).isPresent();
    }

    /**
     * Whether a registry list may be opened as far as its entity goes: the list of an entity only when the viewer may
     * see that entity; a module's own list, of no entity, keeps just its own right, checked by the caller.
     */
    public boolean listOpen(String listCode) {
        return registry.findByList(listCode)
                .map(entity -> visible(entity.code()))
                .orElse(true);
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
        String module = moduleOf.get(entity.code());
        InstalledModules installed = modules;
        return module == null || installed == null || installed.active(module);
    }

    /** The answer to an entity the viewer may not see, the same as to an unknown code. */
    public static ApiException unknownEntity() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.entity_not_found");
    }
}
