package com.smartup24.cms.instance.common.security;

/**
 * The data scope of ADR-0013 as the platform needs it, without knowing the module that keeps it (ADR-0032, 5.1): the
 * md module implements it, so an entity declared with {@code EntityScope.orgUnit(...)} is restricted by the same rule
 * as every scoped list, and {@code common} still depends on no module.
 */
public interface DataScopes {

    /**
     * The row restriction of the user's effective rule: {@code ALL} — none, {@code SUBTREE}/{@code UNITS} — the row's
     * unit in the materialized scope, {@code SELF} — the user's own rows.
     *
     * @param orgUnitColumn the row's unit column with its alias ({@code o.org_unit_id})
     * @param ownerColumn   the row's owner column with its alias, for {@code SELF} ({@code o.created_by})
     */
    ScopeFilter filterFor(long userId, String orgUnitColumn, String ownerColumn);

    /**
     * Whether a record may be put in the unit: under {@code ALL} any existing unit, under {@code SUBTREE}/{@code UNITS}
     * a unit of the user's scope, under {@code SELF} one of the user's own units.
     */
    boolean unitVisible(long userId, long orgUnitId);
}
