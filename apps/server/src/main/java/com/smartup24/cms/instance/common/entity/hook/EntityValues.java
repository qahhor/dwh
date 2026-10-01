package com.smartup24.cms.instance.common.entity.hook;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.FormField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The values of an entity record by field key, typed by the declaration (ADR-0032, 6.5): what a hook and a rule read,
 * and — in {@code beforeSave} — the values a hook may change. {@link #set} takes only a written field of the form and a
 * value its type accepts, so a hook cannot put a value the declaration refuses; a record read before the save is
 * read-only.
 */
public final class EntityValues {

    private final EntityDefinition entity;
    private final Map<String, Object> values;
    private final boolean writable;

    private EntityValues(EntityDefinition entity, Map<String, ?> values, boolean writable) {
        this.entity = entity;
        this.values = new LinkedHashMap<>(values);
        this.writable = writable;
    }

    /** Values a hook may only read: the record before the save, a deleted record. */
    public static EntityValues readOnly(EntityDefinition entity, Map<String, ?> values) {
        return new EntityValues(entity, values, false);
    }

    /** The values of the save, which {@code beforeSave} may change. */
    public static EntityValues writable(EntityDefinition entity, Map<String, ?> values) {
        return new EntityValues(entity, values, true);
    }

    /** Whether the record has a value for the key (null included). */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    public @Nullable Object get(String key) {
        return values.get(key);
    }

    public @Nullable String text(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** A number or the amount of money, exactly. */
    public @Nullable BigDecimal decimal(String key) {
        Object value = values.get(key);
        if (value instanceof Map<?, ?> money) value = money.get("amount");
        if (value == null || String.valueOf(value).isBlank()) return null;
        return value instanceof BigDecimal number
                ? number
                : new BigDecimal(String.valueOf(value).strip());
    }

    /** Money as the API gives it: {@code {"amount": "1250.00", "currency": "UZS"}}. */
    public @Nullable Map<String, Object> money(String key) {
        Object value = values.get(key);
        if (!(value instanceof Map<?, ?> money)) return null;
        Map<String, Object> copy = new LinkedHashMap<>();
        money.forEach((name, part) -> copy.put(String.valueOf(name), part));
        return Collections.unmodifiableMap(copy);
    }

    public @Nullable LocalDate date(String key) {
        String text = text(key);
        return text == null || text.isBlank() ? null : LocalDate.parse(text.strip());
    }

    /** The key of the row a reference names. */
    public @Nullable Long ref(String key) {
        Object value = values.get(key);
        if (value == null) return null;
        return value instanceof Number number
                ? number.longValue()
                : Long.valueOf(String.valueOf(value).strip());
    }

    /** The keys of several references, in order; empty without them. */
    public List<Long> refs(String key) {
        List<Long> keys = new ArrayList<>();
        if (values.get(key) instanceof List<?> list) {
            list.forEach(item ->
                    keys.add(item instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(item))));
        }
        return List.copyOf(keys);
    }

    public @Nullable Boolean bool(String key) {
        Object value = values.get(key);
        return value == null ? null : value instanceof Boolean flag ? flag : Boolean.valueOf(String.valueOf(value));
    }

    /** A JSON value as it is: a map or a list. */
    public @Nullable Object json(String key) {
        return values.get(key);
    }

    /**
     * Changes a written field of the form; the value must be one its type accepts (ADR-0032, 6.5).
     *
     * @throws UnsupportedOperationException on values that are only read
     * @throws IllegalArgumentException      for a key that is no written field, or a value its type refuses
     */
    public void set(String key, @Nullable Object value) {
        if (!writable) {
            throw new UnsupportedOperationException(entity.code() + ": these values are only read");
        }
        FormField field = entity.fieldsByKey().get(key);
        if (field == null || field.attribute() != null || field.computed()) {
            throw new IllegalArgumentException(entity.code() + ": " + key + " is no written field of the form");
        }
        if (value != null) {
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put(key, value);
            boolean refused = EntityValidator.problems(entity, probe, true).stream()
                    .anyMatch(problem -> problem.field().equals(key));
            if (refused) {
                throw new IllegalArgumentException(entity.code() + ": the value of " + key + " is refused by its type");
            }
        }
        values.put(key, value);
    }

    /** The values as they are now, by key. */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
