package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.EntityDefinition;
import org.jspecify.annotations.Nullable;

/**
 * The field that names the records of some entities on their screens — a live form names them by the field its
 * administrator chose or by the record's number (ADR-0041). An entity no source names is named as before: by the first
 * filled text field of its layout.
 */
public interface EntityTitles {
    /** The key of the field that names a record of {@code entity}, or null when this source does not name it. */
    @Nullable
    String titleField(EntityDefinition entity);
}
