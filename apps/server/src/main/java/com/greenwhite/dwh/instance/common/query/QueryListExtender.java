package com.greenwhite.dwh.instance.common.query;

import java.util.List;

/**
 * Adds fields to a registry list at request time, after the fields the module declared in code — the custom
 * fields an administrator defined for the list's entity (ADR-0019, 2.3). It lives in {@code common} and is
 * implemented by a module, so the registry does not depend on modules.
 */
public interface QueryListExtender {

    /** Fields to add to {@code list}; empty when the list takes none. */
    List<QueryField> extraFields(QueryList list);
}
