package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.formula.Formula;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The form logic of an entity, written in formulas (ADR-0042): computed fields in the order they are evaluated, the
 * defaults of new records, when a field is shown, required or read-only, and the rules every save must meet. Only a
 * live form declares it today; the general runtime applies it on every write path and form-meta gives the checked
 * trees to the web, which shows the same values and states while the person types. The server stays authoritative.
 *
 * @param computed the computed fields, each after the computed fields it reads
 * @param defaults the default of a new record's field it is sent without, by field key
 * @param visible  the condition under which a field is shown — a hidden field keeps no value and is not required
 * @param required the condition under which an optional field is required
 * @param readonly the condition, over the record as it is stored, under which a saved record's field does not change
 * @param rules    the rules, in their order
 */
public record EntityLogic(
        List<Computed> computed,
        Map<String, Formula> defaults,
        Map<String, Formula> visible,
        Map<String, Formula> required,
        Map<String, Formula> readonly,
        List<Rule> rules) {

    /** A form without logic. */
    public static final EntityLogic NONE =
            new EntityLogic(List.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());

    /** A computed field and its formula. */
    public record Computed(String field, Formula formula) {}

    /**
     * A rule: its key, the field its problem is shown at (null: the whole record), its yes/no formula — only false
     * fails — the dictionary key of its message for the screens and the message in each language for the answer.
     */
    public record Rule(
            String key, @Nullable String field, Formula formula, String messageKey, Map<String, String> messages) {
        public Rule {
            messages = Map.copyOf(messages);
        }
    }

    public EntityLogic {
        computed = List.copyOf(computed);
        defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
        visible = Collections.unmodifiableMap(new LinkedHashMap<>(visible));
        required = Collections.unmodifiableMap(new LinkedHashMap<>(required));
        readonly = Collections.unmodifiableMap(new LinkedHashMap<>(readonly));
        rules = List.copyOf(rules);
    }

    /** Whether the entity has no logic at all. */
    public boolean empty() {
        return computed.isEmpty()
                && defaults.isEmpty()
                && visible.isEmpty()
                && required.isEmpty()
                && readonly.isEmpty()
                && rules.isEmpty();
    }

    /** The keys of the computed fields: the server writes them, a save never does. */
    public Set<String> computedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        computed.forEach(item -> keys.add(item.field()));
        return keys;
    }

    /** The formula that computes {@code key}, or null when the field is not computed. */
    public @Nullable Formula computedFormula(String key) {
        return computed.stream()
                .filter(item -> item.field().equals(key))
                .map(Computed::formula)
                .findFirst()
                .orElse(null);
    }
}
