package com.smartup24.cms.instance.common.query;

import java.util.List;

/**
 * Lists built from other declarations rather than declared as {@link QueryList} beans: the lists of the entities,
 * derived from their fields (ADR-0032, 3.4; plan 10/10, item 5.1). It lives in {@code common.query}, so the registry
 * does not depend on the entity model.
 */
public interface QueryListSource {

    /** The lists, complete except for the request-time extension fields. */
    List<QueryList> lists();
}
