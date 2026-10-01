package com.smartup24.cms.instance.common.query;

/**
 * Gives a declared list field its values as they are at request time — the items of an enumeration read from its
 * reference entity (ADR-0032, 4.5). It lives in {@code common} and is implemented next to the entity model, so the
 * registry depends on neither.
 */
public interface QueryFieldResolver {

    /** The field of {@code list} as it is now; {@code field} itself when this resolver has nothing to add. */
    QueryField resolve(QueryList list, QueryField field);
}
