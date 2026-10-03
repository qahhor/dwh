package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A condition over the values of a form (ADR-0032, 4.4), a subset of the list filter's DSL (ADR-0016): {@code eq},
 * {@code ne}, {@code in}, {@code empty} and {@code not_empty} over select, enumeration, yes/no and reference fields.
 * Every group must hold; a group holds when any of its clauses does, so a plain clause is a group of one and
 * {@code {"any": [...]}} is a group of several. The form evaluates it on every change and the server on save, with
 * the same {@link #test}, so the two agree.
 *
 * @param groups the groups, joined by "and"; each one's clauses joined by "or"
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record FieldCondition(List<List<Clause>> groups) {

    /** The operations a condition takes. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public enum Op {
        EQ,
        NE,
        IN,
        EMPTY,
        NOT_EMPTY;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The kinds of field a condition may look at: one plain value each, picked from known ones. */
    public static final Set<FieldType> TESTED_TYPES =
            Set.of(FieldType.SELECT, FieldType.ENUM, FieldType.BOOLEAN, FieldType.REF);

    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{0,63}$");

    /**
     * One test of one field's value.
     *
     * @param field  the key of the field it looks at
     * @param op     the operation
     * @param values the values compared as text ({@code "true"}, {@code "5"}, a code); one for {@code eq}/{@code ne},
     *               some for {@code in}, none for {@code empty}/{@code not_empty}
     */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public record Clause(String field, Op op, List<String> values) {
        public Clause {
            Objects.requireNonNull(op, "op");
            if (field == null || !KEY.matcher(field).matches()) {
                throw new IllegalArgumentException("Bad condition field: " + field);
            }
            values = List.copyOf(values);
            int expected = switch (op) {
                case EQ, NE -> 1;
                case EMPTY, NOT_EMPTY -> 0;
                case IN -> -1;
            };
            if (expected >= 0 ? values.size() != expected : values.isEmpty()) {
                throw new IllegalArgumentException("Condition on " + field + ": " + op.wire() + " takes "
                        + (expected < 0 ? "some values" : expected + " value(s)"));
            }
        }

        boolean test(@Nullable Object value) {
            String text = value == null ? "" : String.valueOf(value);
            return switch (op) {
                case EQ -> text.equals(values.getFirst());
                case NE -> !text.equals(values.getFirst());
                case IN -> values.contains(text);
                case EMPTY -> text.isBlank();
                case NOT_EMPTY -> !text.isBlank();
            };
        }
    }

    public FieldCondition {
        List<List<Clause>> copied = new ArrayList<>();
        for (List<Clause> group : groups) {
            if (group.isEmpty()) {
                throw new IllegalArgumentException("A condition group holds a clause");
            }
            copied.add(List.copyOf(group));
        }
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("A condition holds a clause");
        }
        groups = List.copyOf(copied);
    }

    /** {@code field = value}. */
    public static FieldCondition eq(String field, String value) {
        return of(new Clause(field, Op.EQ, List.of(value)));
    }

    /** {@code field <> value}; an empty field differs from every value. */
    public static FieldCondition ne(String field, String value) {
        return of(new Clause(field, Op.NE, List.of(value)));
    }

    /** The field holds one of {@code values}. */
    public static FieldCondition in(String field, String... values) {
        return of(new Clause(field, Op.IN, List.of(values)));
    }

    public static FieldCondition empty(String field) {
        return of(new Clause(field, Op.EMPTY, List.of()));
    }

    public static FieldCondition notEmpty(String field) {
        return of(new Clause(field, Op.NOT_EMPTY, List.of()));
    }

    /** Any of the clauses. */
    public static FieldCondition any(Clause... clauses) {
        return new FieldCondition(List.of(List.of(clauses)));
    }

    /** This condition and {@code other}: every group of both. */
    public FieldCondition and(FieldCondition other) {
        List<List<Clause>> all = new ArrayList<>(groups);
        all.addAll(other.groups);
        return new FieldCondition(all);
    }

    /** The keys of the fields the condition looks at. */
    public Set<String> fields() {
        Set<String> keys = new LinkedHashSet<>();
        groups.forEach(group -> group.forEach(clause -> keys.add(clause.field())));
        return keys;
    }

    /** Whether the condition holds over the form's values by field key. */
    public boolean test(Map<String, ?> values) {
        for (List<Clause> group : groups) {
            if (group.stream().noneMatch(clause -> clause.test(values.get(clause.field())))) {
                return false;
            }
        }
        return true;
    }

    private static FieldCondition of(Clause clause) {
        return new FieldCondition(List.of(List.of(clause)));
    }
}
