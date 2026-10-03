package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
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
@PlatformApi(since = "1.0", stability = Stability.STABLE)
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

    /** The runtime list of an entity (ADR-0032, 4.6): {@code /entities/ms.notes}. */
    public static String entityPath(String entity) {
        return "/entities/" + entity;
    }

    /** The same reference with one row read from {@code {readPath}/{key}}. */
    public QueryRef readBy(String readPath) {
        return new QueryRef(path, labelField, keyField, paged, readPath);
    }

    private static void requirePath(String path) {
        Objects.requireNonNull(path, "path");
        // A dot only in an entity's code: /entities/md.users (ADR-0032, 4.6).
        if (!path.matches("^/[a-z0-9/_.-]+$")) {
            throw new IllegalArgumentException("Bad reference path: " + path);
        }
    }
}
