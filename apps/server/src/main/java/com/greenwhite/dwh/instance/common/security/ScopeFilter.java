package com.greenwhite.dwh.instance.common.security;

/**
 * Ограничение выборки строк по скоупу данных (ADR-0013).
 *
 * Возвращается как готовый фрагмент SQL, а не как список идентификаторов:
 * фильтрация после выборки ломает пагинацию (страница в 50 строк после
 * фильтра станет короче) и счётчики, а на витринах Этапа 2 — ещё и скорость.
 *
 * Фрагмент дописывается к запросу вида {@code ... where 1=1}, поэтому всегда
 * начинается с {@code and}. Пустой фрагмент означает «ограничений нет»
 * (правило ALL) — это самый частый случай, и он не стоит ничего.
 */
public record ScopeFilter(String sql, boolean bindsUserId, Long userId) {

    private static final ScopeFilter UNRESTRICTED = new ScopeFilter("", false, null);

    /** Правило ALL: запрос не меняется. */
    public static ScopeFilter unrestricted() {
        return UNRESTRICTED;
    }

    /** Правила SUBTREE и UNITS: строка видна, если её узел материализован в скоупе. */
    public static ScopeFilter byOrgUnit(String orgUnitColumn, Long userId) {
        return new ScopeFilter(
                " and " + orgUnitColumn + " in ("
                        + "select org_unit_id from md_effective_scope where user_id = :scopeUserId)",
                true, userId);
    }

    /** Правило SELF: видны только собственные строки. */
    public static ScopeFilter byOwner(String ownerColumn, Long userId) {
        return new ScopeFilter(" and " + ownerColumn + " = :scopeUserId", true, userId);
    }

    public boolean isUnrestricted() {
        return sql.isEmpty();
    }
}
