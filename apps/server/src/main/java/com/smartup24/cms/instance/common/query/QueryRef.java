package com.smartup24.cms.instance.common.query;

import java.util.Objects;

/**
 * Where a reference field's values come from (ADR-0019, 2.4): the list endpoint under {@code /api/v1} that
 * gives the rows to pick from, the property naming a row and the property holding its key.
 *
 * @param path       the endpoint, e.g. {@code /iam/users}; its own rights and data scope decide what is offered
 * @param labelField the row property shown to the person, e.g. {@code name}
 * @param keyField   the row property the field holds, e.g. {@code id}
 * @param paged      true when the endpoint answers keyset pages with {@code q}; false when it answers the whole list
 */
public record QueryRef(String path, String labelField, String keyField, boolean paged) {

    public QueryRef {
        Objects.requireNonNull(path, "path");
        if (!path.matches("^/[a-z0-9/_-]+$")) {
            throw new IllegalArgumentException("Bad reference path: " + path);
        }
        Objects.requireNonNull(labelField, "labelField");
        Objects.requireNonNull(keyField, "keyField");
    }

    /** A paged list keyed by {@code id}. */
    public static QueryRef paged(String path, String labelField) {
        return new QueryRef(path, labelField, "id", true);
    }

    /** A whole (short) list keyed by {@code id}, filtered on the screen. */
    public static QueryRef whole(String path, String labelField) {
        return new QueryRef(path, labelField, "id", false);
    }
}
