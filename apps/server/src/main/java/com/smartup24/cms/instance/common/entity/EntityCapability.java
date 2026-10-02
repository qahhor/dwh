package com.smartup24.cms.instance.common.entity;

import java.util.Locale;

/** What the platform provides for an entity once it is declared (ADR-0019, 2.1). */
public enum EntityCapability {
    /** Administrator-defined fields in its attributes. */
    CUSTOM_FIELDS,
    /** Saved views of its list. */
    SAVED_VIEWS,
    /** Export of its list to a file. */
    EXPORT,
    /** The record history tab. */
    HISTORY,
    /** Bulk actions on its list. */
    BULK,
    /**
     * Records are archived and restored (ADR-0032, 5.4): the table has {@code archived_at} and {@code archived_by}, the
     * list leaves archived records out unless its {@code archived} field is filtered, a read by id still finds them.
     */
    ARCHIVE,
    /**
     * Records are imported from an xlsx file (ADR-0032, 10.1): a template from the declaration, a dry run that checks
     * every row without writing, and an upsert by the declared key ({@link Entity#importKey}) in the background, every
     * row through the same steps as a save of the runtime.
     */
    IMPORT,
    /**
     * Its records are found by the global search (ADR-0032, 10.3): declared with {@code Entity.search(...)}, which
     * names the fields of its search documents.
     */
    SEARCH;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
