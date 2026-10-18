package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import java.util.List;
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

    /** Rechecks the exported row ids against today's scope before a stored file can be downloaded (ADR-0018). */
    boolean visible(Set<String> ids);

    /**
     * Options beyond filter, sort and search that the screen passes and the export keeps, e.g. the files
     * list's {@code scope}. Anything else the client sends is refused.
     */
    default Set<String> options() {
        return Set.of();
    }

    /**
     * Checks the option values before the job starts, so a bad value is refused at once and not in the job.
     * Addresses are the option names; the caller prefixes them with {@code options.}.
     */
    default List<FieldErrorItem> checkOptions(Map<String, String> options) {
        return List.of();
    }

    /**
     * The rows of a document an item holds under a key — the lines of an order (ADR-0043) — each written to a sheet of
     * its own after the list: the title of the sheet and the fields of a row. None for a list of plain records.
     */
    record Detail(String key, String labelKey, List<QueryField> fields) {
        public Detail {
            fields = List.copyOf(fields);
        }
    }

    /** The rows of documents the items of a page hold, in the order of their sheets; none by default. */
    default List<Detail> details() {
        return List.of();
    }

    /** One page for the signed-in person, exactly as the list endpoint would return it. */
    KeysetPage<?> page(
            int limit, String cursor, String filter, String sort, String search, Map<String, String> options);
}
