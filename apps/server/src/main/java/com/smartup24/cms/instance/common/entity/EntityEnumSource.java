package com.smartup24.cms.instance.common.entity;

/**
 * The items of enumerations whose reference is no declared entity: the reference lists an administrator keeps without a
 * release (ADR-0041). A module implements it, so {@code common} depends on no module; {@link EntityEnums} caches what it
 * answers by reference and language.
 */
public interface EntityEnumSource {

    /** Whether {@code reference} names one of this source's lists. */
    boolean owns(String reference);

    /**
     * The items of {@code reference} named in {@code language}, in their order: an inactive item is archived — still
     * named for the records that hold it, no longer offered. {@link EntityEnums.Items#NONE} for an unknown list.
     */
    EntityEnums.Items items(String reference, String language);
}
