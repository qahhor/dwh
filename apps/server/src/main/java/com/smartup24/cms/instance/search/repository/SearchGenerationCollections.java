package com.smartup24.cms.instance.search.repository;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The collections of a search generation, one per entity it indexes (ADR-0032, 10.3), as one column of a query and back
 * into a map: entity code → collection name, in the order of the codes. Neither a code nor a collection name holds a
 * comma or an equals sign (both are checked by the schema).
 */
public final class SearchGenerationCollections {

    private SearchGenerationCollections() {}

    /** The column {@code collections} of the generation aliased {@code generation} ({@code g}). */
    public static String column(String generation) {
        return "(select string_agg(c.entity_type || '=' || c.collection, ',' order by c.entity_type)"
                + " from search_generation_collections c where c.generation_id = " + generation + ".id) as collections";
    }

    /** The map the column holds; empty without collections. */
    public static Map<String, String> parse(String column) {
        Map<String, String> collections = new TreeMap<>();
        if (column == null || column.isBlank()) return Collections.unmodifiableMap(collections);
        for (String pair : column.split(",")) {
            int split = pair.indexOf('=');
            if (split <= 0) throw new IllegalStateException("Bad search generation collections");
            collections.put(pair.substring(0, split), pair.substring(split + 1));
        }
        return Collections.unmodifiableMap(collections);
    }

    /** An unmodifiable copy in the order of the codes. */
    public static Map<String, String> ordered(Map<String, String> collections) {
        return Collections.unmodifiableMap(new TreeMap<>(collections));
    }

    /**
     * The predicate that keeps the projection versions {@code v} of the types the generation {@code generation} (an
     * expression of its id) has a collection for: a type without one is never delivered to it, so it neither blocks its
     * activation nor counts as pending.
     */
    public static String indexed(String generation) {
        return "exists(select 1 from search_generation_collections gc where gc.generation_id = " + generation
                + " and gc.entity_type = v.entity_type)";
    }
}
