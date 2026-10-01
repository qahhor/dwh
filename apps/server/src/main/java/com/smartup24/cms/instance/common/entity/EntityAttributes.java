package com.smartup24.cms.instance.common.entity;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The administrator's custom fields of an entity (ADR-0019, 2.3) as the runtime checks them (ADR-0032, 6.3, step 8):
 * the md module keeps their definitions and implements this, so {@code common} depends on no module.
 */
public interface EntityAttributes {

    /**
     * The record's custom field values as they are kept.
     *
     * @param entityType the entity type of the custom fields ({@code NOTE})
     * @param attributes the values the client sent by field code
     * @throws com.smartup24.cms.instance.common.error.ApiException 422 with a problem per field, addressed
     *     {@code attributes.<code>}
     */
    @Nullable
    Map<String, Object> checked(String entityType, @Nullable Map<String, Object> attributes);
}
