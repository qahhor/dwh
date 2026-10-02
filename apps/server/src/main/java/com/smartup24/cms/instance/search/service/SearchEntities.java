package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The entities the global search finds (ADR-0032, 10.3; plan 10/10, item 5.8): every declared entity with the SEARCH
 * capability, in the order of their codes. A viewer searches those whose module is switched on and whose {@code view}
 * right they hold — the same gate as the entity's runtime (ADR-0032, 6.3, step 1) — and finds only the records of their
 * scope.
 */
@Component
public class SearchEntities {

    private final Map<String, SearchEntity> entities = new TreeMap<>();
    private final @Nullable InstalledModules modules;

    @Autowired
    public SearchEntities(EntityRegistry registry, ObjectProvider<InstalledModules> modules) {
        this(registry.all(), modules.getIfAvailable());
    }

    public SearchEntities(List<EntityDefinition> declared, @Nullable InstalledModules modules) {
        for (EntityDefinition entity : declared) {
            if (entity.capabilities().contains(EntityCapability.SEARCH)) {
                entities.put(entity.code(), new SearchEntity(entity));
            }
        }
        this.modules = modules;
    }

    /** Every entity the search indexes, by code. */
    public List<SearchEntity> all() {
        return List.copyOf(entities.values());
    }

    /** The codes of every entity the search indexes, in order. */
    public List<String> codes() {
        return List.copyOf(entities.keySet());
    }

    public Optional<SearchEntity> find(String code) {
        return Optional.ofNullable(entities.get(code));
    }

    /** The entities the caller may search: module on and the {@code view} right held. */
    public List<SearchEntity> visible() {
        return entities.values().stream().filter(this::visible).toList();
    }

    /** Whether the caller may search the entity. */
    public boolean visible(SearchEntity entity) {
        EntityDefinition.EntityMenu menu = entity.definition().menu();
        String module = menu == null ? null : menu.module();
        InstalledModules installed = modules;
        boolean active = module == null || installed == null || installed.active(module);
        return active && SecurityContext.hasPermission(entity.definition().form(), "view");
    }
}
