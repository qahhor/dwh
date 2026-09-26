package com.smartup24.cms.instance.common.entity;

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
    BULK;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
