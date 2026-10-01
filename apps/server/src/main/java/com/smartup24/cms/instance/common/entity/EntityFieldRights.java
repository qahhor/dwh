package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldAccess;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The rights on an entity's fields as the signed-in viewer holds them (ADR-0032, 5.2; plan 10/10, item 5.3). A field
 * whose {@code requires} the viewer lacks does not exist for them: {@code form-meta} leaves it out, the list and its
 * export lose the column (its list field carries the same right), the history hides it ({@link #hidden}), a record
 * read loses the property ({@link #project}) and a value sent for it is refused exactly as an unknown property
 * ({@link #checkWrite}). A field whose {@code readonlyUnless} the viewer lacks is read-only: {@code form-meta} marks
 * it, and a changed value is refused. A webhook has no viewer, so its data leaves out every restricted field
 * ({@link #forEveryone}).
 *
 * <p>Custom fields and the record's own properties carry no field rights: they are open to whoever sees the record.
 */
public final class EntityFieldRights {

    /** The field error of a property the viewer may not see, the same as for one that does not exist. */
    public static final String UNKNOWN_FIELD = "unknown_field";

    /**
     * The field error of a changed value of a field the viewer may not write: the same code and message as for a field
     * read-only by its declaration (ADR-0032, 4.4).
     */
    public static final String READONLY = EntityValidator.READONLY;

    private EntityFieldRights() {}

    /** The rights on the entity's field {@code key}; a key the model does not declare is open. */
    public static FieldAccess of(EntityDefinition entity, String key) {
        EntityModel model = entity.model();
        if (model == null) return FieldAccess.OPEN;
        return model.fields().stream()
                .filter(field -> field.key().equals(key))
                .map(EntityField::access)
                .findFirst()
                .orElse(FieldAccess.OPEN);
    }

    /** Whether the viewer holds the right the field needs to exist. */
    public static boolean visible(FieldAccess access) {
        return !access.restricted()
                || SecurityContext.hasPermission(
                        Objects.requireNonNull(access.requiredForm()), Objects.requireNonNull(access.requiredAction()));
    }

    /** Whether the viewer may change the field's value; a field they cannot see they cannot write either. */
    public static boolean writable(FieldAccess access) {
        return visible(access)
                && (!access.guardsWriting()
                        || SecurityContext.hasPermission(
                                Objects.requireNonNull(access.readonlyForm()),
                                Objects.requireNonNull(access.readonlyAction())));
    }

    /** The keys of the fields that do not exist for the viewer. */
    public static Set<String> hidden(EntityDefinition entity) {
        return keys(entity, access -> !visible(access));
    }

    /** The keys of the fields the viewer sees but may not change. */
    public static Set<String> readonly(EntityDefinition entity) {
        return keys(entity, access -> visible(access) && !writable(access));
    }

    /** The keys of the fields that need a right to exist, whoever looks: what a webhook's data leaves out. */
    public static Set<String> restricted(EntityDefinition entity) {
        return keys(entity, FieldAccess::restricted);
    }

    /**
     * A record as the viewer may read it: without the properties of the fields that do not exist for them, nor their
     * labels (ADR-0032, 4.6).
     */
    public static Map<String, Object> project(EntityDefinition entity, Map<String, ?> record) {
        return without(record, hidden(entity));
    }

    /** A record as everyone may read it, for a webhook's data: without any restricted field. */
    public static Map<String, Object> forEveryone(EntityDefinition entity, Map<String, ?> record) {
        return without(record, restricted(entity));
    }

    /**
     * Refuses what the viewer may not write: 422 {@code record_fields_invalid} with {@code unknown_field} for a field
     * that does not exist for them and {@code readonly} for a changed value of a field they may not change.
     *
     * @param values  the record's values by field key, as sent
     * @param current the record as it is, or null for a new record — then any value of a read-only field is a change
     */
    public static void checkWrite(EntityDefinition entity, Map<String, ?> values, @Nullable Map<String, ?> current) {
        List<FieldErrorItem> errors = writeProblems(entity, values, current);
        if (!errors.isEmpty()) {
            throw ApiException.validation("error.common.record_fields_invalid", errors);
        }
    }

    /** The problems {@link #checkWrite} refuses, in the order of the declared fields. */
    public static List<FieldErrorItem> writeProblems(
            EntityDefinition entity, Map<String, ?> values, @Nullable Map<String, ?> current) {
        List<FieldErrorItem> errors = new ArrayList<>();
        EntityModel model = entity.model();
        if (model == null) return errors;
        for (EntityField field : model.fields()) {
            String key = field.key();
            if (!values.containsKey(key)) continue;
            if (!visible(field.access())) {
                errors.add(FieldErrorItem.keyed(key, UNKNOWN_FIELD, "error.field.unknown"));
            } else if (!writable(field.access()) && changes(field.formField(), values.get(key), current, key)) {
                errors.add(FieldErrorItem.keyed(key, READONLY, "error.field.readonly"));
            }
        }
        return errors;
    }

    /**
     * A value equal to the current one is kept, so a client may send the whole record back (ADR-0032, 4.4); a form
     * field compares by its type ({@link FieldValueRules#same}), so an empty text is no value and {@code 12.50} is
     * {@code 12.5}. On creation there is no current value: anything but an empty one is a change.
     */
    private static boolean changes(
            @Nullable FormField form, @Nullable Object value, @Nullable Map<String, ?> current, String key) {
        Object now = current == null ? null : current.get(key);
        if (form != null) return !FieldValueRules.same(form, value, now);
        if (value == null || now == null) return (value == null) != (now == null);
        return !String.valueOf(value).equals(String.valueOf(now));
    }

    private static Set<String> keys(EntityDefinition entity, Predicate<FieldAccess> which) {
        EntityModel model = entity.model();
        if (model == null) return Set.of();
        Set<String> keys = new LinkedHashSet<>();
        for (EntityField field : model.fields()) {
            if (which.test(field.access())) keys.add(field.key());
        }
        return keys;
    }

    private static Map<String, Object> without(Map<String, ?> record, Set<String> keys) {
        Map<String, Object> shown = new LinkedHashMap<>();
        record.forEach((key, value) -> {
            if (keys.contains(key) || keys.contains(labelOwner(key))) return;
            if ("labels".equals(key) && value instanceof Map<?, ?> labels) {
                Map<Object, Object> kept = new LinkedHashMap<>(labels);
                kept.keySet().removeIf(keys::contains);
                shown.put(key, kept);
            } else {
                shown.put(key, value);
            }
        });
        return shown;
    }

    /** {@code assigneeId$label} belongs to {@code assigneeId} (ADR-0032, 3.4). */
    private static String labelOwner(String key) {
        int at = key.indexOf('$');
        return at < 0 ? key : key.substring(0, at);
    }
}
