package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the audit log keeps of an entity record (plan 10/10, item 5.0): every field the entity declares, under the
 * key its form and list use, and every custom field it has now, under its {@code cf…} key — so the record's
 * history shows each of them with the form's label. Empty fields are left out of the row.
 */
public final class EntityAuditRow {

    private EntityAuditRow() {}

    /**
     * The audit row of one record.
     *
     * @param entity     the entity with its custom fields ({@link EntityRegistry#resolve})
     * @param values     the record's declared values by field key
     * @param attributes the record's custom field values by code, or null
     */
    public static Map<String, Object> of(
            EntityDefinition entity, Map<String, ?> values, @Nullable Map<String, ?> attributes) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (FormField field : entity.fields()) {
            Object value = field.attribute() == null
                    ? values.get(field.key())
                    : attributes == null ? null : attributes.get(field.attribute());
            if (value != null && !(value instanceof String text && text.isEmpty())) {
                row.put(field.key(), value);
            }
        }
        return row;
    }

    /** The keys whose values differ between two rows, in the entity's order: the columns an update changed. */
    public static List<String> changed(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                changed.add(key);
            }
        }
        return changed;
    }
}
