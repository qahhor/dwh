package com.smartup24.cms.instance.common.security;

/**
 * The scope keys a search document carries (ADR-0032, 10.3; ADR-0013, 2.5), as SQL the search module reads them with:
 * the org units of the users whose participation makes a record visible. A user stands in a unit by the home unit or
 * an additional one — the same rule as {@link ScopeFilter#userByOrgUnit} — so a viewer with the rule
 * {@code SUBTREE}/{@code UNITS} sees the record when one of these units is in their materialized scope.
 */
public final class ScopeKeys {

    private ScopeKeys() {}

    /** The units ({@code bigint[]}, each once, in order) of the users that {@code usersSql} gives as {@code bigint[]}. */
    public static String unitsOf(String usersSql) {
        return """
                array(select scope_unit.id from (
                    select scope_ku.org_unit_id as id from md_users scope_ku
                    where scope_ku.id = any(%1$s) and scope_ku.org_unit_id is not null
                    union
                    select scope_kuou.org_unit_id from md_user_org_units scope_kuou
                    where scope_kuou.user_id = any(%1$s)
                ) scope_unit order by scope_unit.id)""".formatted(usersSql);
    }
}
