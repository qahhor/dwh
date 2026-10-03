package com.smartup24.cms.platform.api.entity;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.platform.api.entity.field.FieldSource;

/**
 * An entity whose rows are the items of enumerations (ADR-0032, 4.5): its code column (unique, the value an
 * enumeration keeps — portable between installations, readable in an import, an export and a webhook), the column of
 * the item's name and the column of its order. Up to {@link #MAX_ITEMS} items: a longer list is a reference
 * ({@code REF}), not an enumeration.
 *
 * @param codeColumn  the item's code ({@code code})
 * @param nameColumn  the item's name ({@code name})
 * @param orderColumn the item's place in the list ({@code sort_order}); items of one place go by code
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record EntityReference(String codeColumn, String nameColumn, String orderColumn) {

    /** The most items an enumeration offers. */
    public static final int MAX_ITEMS = 500;

    public EntityReference {
        for (String column : new String[] {codeColumn, nameColumn, orderColumn}) {
            if (column == null || !FieldSource.IDENTIFIER.matcher(column).matches()) {
                throw new IllegalArgumentException("Bad reference column: " + column);
            }
        }
    }
}
