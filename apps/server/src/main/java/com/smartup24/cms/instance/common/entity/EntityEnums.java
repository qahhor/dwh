package com.smartup24.cms.instance.common.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The items of the reference entities (ADR-0032, 4.5): an enumeration keeps an item's code and shows its name. A
 * reference is read whole — at most {@link EntityReference#MAX_ITEMS} items, in its order — and kept in the
 * {@value #CACHE} cache; a change of a reference clears it on every node of the cluster ({@link #evict}, ADR-0025).
 */
@Component
public class EntityEnums {

    /** The cache of the items by reference entity code. */
    public static final String CACHE = "entityEnums";

    private final Map<String, EntityDefinition> references;
    private final JdbcClient jdbc;
    private final @Nullable CacheManager caches;

    @Autowired
    public EntityEnums(List<EntityDefinition> entities, JdbcClient jdbc, ObjectProvider<CacheManager> caches) {
        this(entities, jdbc, caches.getIfAvailable());
    }

    /** Reads the references {@code entities} declare; without {@code caches} every call reads the table. */
    public EntityEnums(List<EntityDefinition> entities, JdbcClient jdbc, @Nullable CacheManager caches) {
        this.references = entities.stream()
                .filter(entity -> entity.model() != null && entity.model().reference() != null)
                .collect(Collectors.toUnmodifiableMap(EntityDefinition::code, Function.identity()));
        this.jdbc = jdbc;
        this.caches = caches;
    }

    /** Whether {@code code} names a declared reference entity. */
    public boolean isReference(String code) {
        return references.containsKey(code);
    }

    /** The items of the reference {@code code}: name by code, in the reference's order; empty for an unknown code. */
    public Map<String, String> items(String code) {
        EntityDefinition entity = references.get(code);
        if (entity == null) return Map.of();
        Cache cache = caches == null ? null : caches.getCache(CACHE);
        if (cache == null) return read(entity);
        Map<String, String> cached = cache.get(code, () -> read(entity));
        return cached == null ? Map.of() : cached;
    }

    /** The name of the item {@code value} of the reference {@code code}. */
    public Optional<String> name(String code, String value) {
        return Optional.ofNullable(items(code).get(value));
    }

    /** Forgets the items of the reference {@code code} on every node, after a change of it. */
    public void evict(String code) {
        Cache cache = caches == null ? null : caches.getCache(CACHE);
        if (cache != null) {
            cache.evict(code);
        }
    }

    private Map<String, String> read(EntityDefinition entity) {
        EntityModel model = entity.model();
        EntityReference reference = model == null ? null : model.reference();
        if (model == null || reference == null) return Map.of();
        // Identifiers come from the checked declaration (EntityReference, EntityModel); nothing here is a value.
        String sql = "select " + reference.codeColumn() + " as code, " + reference.nameColumn() + " as name from "
                + model.table() + " order by " + reference.orderColumn() + ", " + reference.codeColumn() + " limit "
                + (EntityReference.MAX_ITEMS + 1);
        Map<String, String> items = new LinkedHashMap<>();
        jdbc.sql(sql).query((RowCallbackHandler) rs -> items.put(rs.getString("code"), rs.getString("name")));
        if (items.size() > EntityReference.MAX_ITEMS) {
            throw new IllegalStateException("Reference " + entity.code() + " holds more than "
                    + EntityReference.MAX_ITEMS + " items: declare a reference (REF) to it instead");
        }
        return Collections.unmodifiableMap(items);
    }
}
