package com.smartup24.cms.instance.common.entity.hook;

import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A save of an entity record as its hooks see it (ADR-0032, 6.5): in {@code beforeSave} the values are checked and may
 * still change; in {@code afterSave} the record is written and its id is known.
 */
public interface EntitySave {

    EntityDefinition entity();

    EntityOperation operation();

    /** The record; null in {@code beforeSave} of a create. */
    @Nullable
    Long id();

    /** The record before the save, read-only; null for a create. */
    @Nullable
    EntityValues before();

    /** The values the save writes: the record as it will be. */
    EntityValues values();

    /** The code of the record action, for {@link EntityOperation#ACTION}; null otherwise. */
    @Nullable
    String action();

    AuditActor actor();

    /** Whether the save changes the field's value. */
    boolean changed(String key);

    /**
     * Refuses the save with a problem of one field ({@code title}, {@code attributes.cfRegion}) or of the whole record
     * ({@code ""}): the problems a hook adds answer one 422 after it returns.
     */
    void reject(String fieldPath, String code, String messageKey, Map<String, ?> params);
}
