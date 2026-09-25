package com.greenwhite.dwh.instance.common.query;

import com.greenwhite.dwh.core.pagination.KeysetPage;

import java.util.Map;
import java.util.Set;

/**
 * Lets a registry list be exported to a file (ADR-0018). The module hands out
 * the same pages its screen gets — the same data scope, the same field rights,
 * the same objects the client receives — so an export never shows more than
 * the list itself. The exporter reads each item's value by the registry field
 * key, which is the item's JSON property name.
 */
public interface QueryListExporter {

    /** The registry list code, e.g. {@code upl.packages}. */
    String code();

    /**
     * Options beyond filter, sort and search that the screen passes and the export keeps, e.g. the files
     * list's {@code scope}. Anything else the client sends is refused.
     */
    default Set<String> options() {
        return Set.of();
    }

    /** One page for the signed-in person, exactly as the list endpoint would return it. */
    KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search, Map<String, String> options);
}
