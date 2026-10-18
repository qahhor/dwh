package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The value of a file field that holds several files (ADR-0043, the files of a live form): a file field kept in a JSON
 * property with {@code maxItems} holds an array of file ids — the record reads each back as {@code {id, name, size,
 * contentType}} — and every other file field one id. A save sends the ids (or the files as read); an empty array is no
 * file.
 */
public final class EntityFileValues {

    private EntityFileValues() {}

    /** Whether the field holds several files. */
    public static boolean several(EntityField field) {
        return field.type() == FieldType.FILE
                && field.form() != null
                && field.form().rules().maxItems() != null;
    }

    /** Whether the field holds several files. */
    public static boolean several(FormField field) {
        return field.type() == FieldType.FILE && field.params().maxItems() != null;
    }

    /**
     * The file ids of a value in their order: none for an empty one, one for a single file, each of an array; empty
     * when an item is no file id.
     */
    public static Optional<List<UUID>> ids(@Nullable Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) return Optional.of(List.of());
        if (!(value instanceof Collection<?> items))
            return FieldValueRules.fileId(value).map(List::of);
        List<UUID> ids = new ArrayList<>(items.size());
        for (Object item : items) {
            Optional<UUID> id = FieldValueRules.fileId(item);
            if (id.isEmpty()) return Optional.empty();
            ids.add(id.get());
        }
        return Optional.of(List.copyOf(ids));
    }

    /** The ids of a value already checked, or none. */
    public static List<UUID> checked(@Nullable Object value) {
        return ids(value).orElse(List.of());
    }

    /**
     * The problem of a value that is not empty: a single file field takes one file id; a field of several an array of
     * distinct file ids, at most {@code maxItems} of them.
     */
    static Optional<FieldErrorItem> problem(FormField field, Object value) {
        String key = field.key();
        boolean array = value instanceof Collection<?>;
        if (!several(field)) {
            return !array && FieldValueRules.fileId(value).isPresent()
                    ? Optional.empty()
                    : Optional.of(FieldErrorItem.keyed(key, EntityValidator.INVALID, "error.field.file_invalid"));
        }
        Optional<List<UUID>> ids = array ? ids(value) : Optional.empty();
        if (ids.isEmpty() || new HashSet<>(ids.get()).size() != ids.get().size()) {
            return Optional.of(FieldErrorItem.keyed(key, EntityValidator.INVALID, "error.field.file_invalid"));
        }
        int most = field.params().maxItems() == null ? 1 : field.params().maxItems();
        if (ids.get().size() > most) {
            return Optional.of(
                    FieldErrorItem.keyed(key, FieldValueRules.TOO_MANY, "error.field.too_many", Map.of("max", most)));
        }
        return Optional.empty();
    }

    /** Whether two values hold the same files in the same order; empty equals empty. */
    static boolean same(@Nullable Object given, @Nullable Object kept) {
        Optional<List<UUID>> a = ids(given);
        Optional<List<UUID>> b = ids(kept);
        return a.isPresent() && a.equals(b);
    }

    /** Whether a value holds no file: none, blank text or an empty array. */
    public static boolean empty(@Nullable Object value) {
        return value == null
                || (value instanceof String text && text.isBlank())
                || (value instanceof Collection<?> items && items.isEmpty());
    }
}
