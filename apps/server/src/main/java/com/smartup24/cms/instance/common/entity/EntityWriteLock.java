package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import org.jspecify.annotations.Nullable;

/**
 * Holds the records of some entities read-only for a while — an archived live form (ADR-0041). Every write path of the
 * general runtime asks each lock before it changes a record: a create, an update, a delete, an archive, a record action
 * and a bulk action. Reads, history and export are never locked.
 */
public interface EntityWriteLock {
    /** The refusal of a write to the records of {@code entity}, or null when they may be written. */
    @Nullable
    ApiException refusal(EntityDefinition entity);
}
