package com.greenwhite.dwh.instance.common.entity;

import java.util.List;

/**
 * Adds fields to an entity's form at request time, after the fields its module declared — the custom fields an
 * administrator defined for its entity type (ADR-0019, 2.3). Implemented by a module, so {@code common} depends
 * on none.
 */
public interface FormFieldExtender {

    /** Fields to add to the form of {@code entity}; empty when it takes none. */
    List<FormField> extraFields(EntityDefinition entity);
}
