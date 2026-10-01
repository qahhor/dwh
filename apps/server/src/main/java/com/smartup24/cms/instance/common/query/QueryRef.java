package com.smartup24.cms.instance.common.query;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Where a reference field's values come from (ADR-0019, 2.4): the list endpoint under {@code /api/v1} that
 * gives the rows to pick from, the property naming a row and the property holding its key.
 *
 * @param path       the endpoint, e.g. {@code /iam/users}; its own rights and data scope decide what is offered
 * @param labelField the row property shown to the person, e.g. {@code name}
 * @param keyField   the row property the field holds, e.g. {@code id}
 * @param paged      true when the endpoint answers keyset pages with {@code q}; false when it answers the whole list
 * @param readPath   the endpoint that reads one row as {@code {readPath}/{key}}, when it is not {@code path}
 *                   (a list paged under {@code /page}, plan 10/10, item 3.5); empty — {@code {path}/{key}}
 */
public record QueryRef(
        String path,
        String labelField,
        String keyField,
        boolean paged,
        @Nullable String readPath) {

    public QueryRef {
        requirePath(path);
        Objects.requireNonNull(labelField, "labelField");
        Objects.requireNonNull(keyField, "keyField");
        if (readPath != null) {
            requirePath(readPath);
        }
    }

    public QueryRef(String path, String labelField, String keyField, boolean paged) {
        this(path, labelField, keyField, paged, null);
    }

    /** A paged list keyed by {@code id}. */
    public static QueryRef paged(String path, String labelField) {
        return new QueryRef(path, labelField, "id", true);
    }

    /** A whole (short) list keyed by {@code id}, filtered on the screen. */
    public static QueryRef whole(String path, String labelField) {
        return new QueryRef(path, labelField, "id", false);
    }

    /** The same reference with one row read from {@code {readPath}/{key}}. */
    public QueryRef readBy(String readPath) {
        return new QueryRef(path, labelField, keyField, paged, readPath);
    }

    private static void requirePath(String path) {
        Objects.requireNonNull(path, "path");
        if (!path.matches("^/[a-z0-9/_-]+$")) {
            throw new IllegalArgumentException("Bad reference path: " + path);
        }
    }
}
