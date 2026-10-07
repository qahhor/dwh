package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.EntityReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * An archivable reference (ADR-0032, 5.4) keeps its archived items, so an old value is still named, but offers only
 * the active ones ({@link Items#offered}), and a new value may not be an archived item.
 *
 * <p>The items are read with no viewer's scope and no right of the reference: they name the values of other entities'
 * fields for whoever fills or reads those. A reference therefore declares {@code EntityScope.all()}, which its model
 * checks when it is declared ({@code EntityModel}), so no row of it is any viewer's secret.
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

    /**
     * The items of a reference: every item's name by code, in the reference's order, and the codes of the archived
     * ones.
     *
     * @param names    the name of every item, archived ones too, by code
     * @param archived the codes of the archived items
     */
    public record Items(Map<String, String> names, Set<String> archived) {

        /** No items: an unknown reference. */
        public static final Items NONE = new Items(Map.of(), Set.of());

        public Items {
            names = Collections.unmodifiableMap(new LinkedHashMap<>(names));
            archived = Set.copyOf(archived);
        }

        /** Items none of which is archived. */
        public static Items active(Map<String, String> names) {
            return new Items(names, Set.of());
        }

        /** The items a new value may take: the active ones, name by code, in the reference's order. */
        public Map<String, String> offered() {
            if (archived.isEmpty()) return names;
            Map<String, String> offered = new LinkedHashMap<>(names);
            offered.keySet().removeAll(archived);
            return Collections.unmodifiableMap(offered);
        }
    }

    /** The items of the reference {@code code}: name by code, in the reference's order; empty for an unknown code. */
    public Map<String, String> items(String code) {
        return all(code).names();
    }

    /** The items of the reference {@code code} with the codes of the archived ones; none for an unknown code. */
    public Items all(String code) {
        EntityDefinition entity = references.get(code);
        if (entity == null) return Items.NONE;
        Cache cache = caches == null ? null : caches.getCache(CACHE);
        if (cache == null) return read(entity);
        Items cached = cache.get(code, () -> read(entity));
        return cached == null ? Items.NONE : cached;
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

    private Items read(EntityDefinition entity) {
        EntityModel model = entity.model();
        EntityReference reference = model == null ? null : model.reference();
        if (model == null || reference == null) return Items.NONE;
        // Identifiers come from the checked declaration (EntityReference, EntityModel); nothing here is a value.
        String archived =
                entity.capabilities().contains(EntityCapability.ARCHIVE) ? "archived_at is not null" : "false";
        String sql = "select " + reference.codeColumn() + " as code, " + reference.nameColumn() + " as name, "
                + archived + " as archived from " + model.table() + " order by " + reference.orderColumn() + ", "
                + reference.codeColumn() + " limit " + (EntityReference.MAX_ITEMS + 1);
        Map<String, String> items = new LinkedHashMap<>();
        Set<String> gone = new LinkedHashSet<>();
        jdbc.sql(sql).query((RowCallbackHandler) rs -> {
            items.put(rs.getString("code"), rs.getString("name"));
            if (rs.getBoolean("archived")) gone.add(rs.getString("code"));
        });
        if (items.size() > EntityReference.MAX_ITEMS) {
            throw new IllegalStateException("Reference " + entity.code() + " holds more than "
                    + EntityReference.MAX_ITEMS + " items: declare a reference (REF) to it instead");
        }
        return new Items(items, gone);
    }
}
