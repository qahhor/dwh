package com.smartup24.cms.instance.common.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A validated list query: conditions with typed values, sort, page size and cursor.
 * Built only by {@link QueryCompiler}; SQL is assembled from registry expressions, values go as parameters.
 */
public record QueryPlan(
        QueryList list,
        List<Condition> conditions,
        QueryField sort,
        boolean descending,
        int limit,
        @Nullable QueryCursor cursor,
        String fingerprint,
        @Nullable String search,
        Set<String> hiddenFields) {

    /**
     * A filter condition; {@code values} are already converted to the field type. {@code group} is the number of
     * an "or" group ({@code {"any": [...]}}); {@code -1} means the condition is joined to the others with "and".
     */
    public record Condition(QueryField field, QueryOp op, List<Object> values, int group) {

        public Condition(QueryField field, QueryOp op, List<Object> values) {
            this(field, op, values, -1);
        }

        public Condition inGroup(int group) {
            return new Condition(field, op, values, group);
        }
    }

    /** A SQL fragment with its parameters. Registry parameters start with {@code q_} so module ones never clash. */
    public record SqlFragment(String sql, Map<String, Object> params) {}

    public QueryPlan {
        conditions = List.copyOf(conditions);
        hiddenFields = Set.copyOf(hiddenFields);
    }

    /**
     * Whether the list returns the field's value to this viewer. If not, the module leaves the field empty when
     * building the response row (field permissions, ADR-0016); an empty field is not written to JSON.
     */
    public boolean shows(String key) {
        return !hiddenFields.contains(key);
    }

    /** Filter conditions: empty or {@code " and ..."}; one "or" group's conditions are bracketed with {@code or}. */
    public SqlFragment where() {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        Map<Integer, List<String>> groups = new LinkedHashMap<>();
        for (int i = 0; i < conditions.size(); i++) {
            Condition condition = conditions.get(i);
            String test = test(condition, "q_f" + i, params);
            if (condition.group() < 0) {
                sql.append(" and ").append(test);
            } else {
                groups.computeIfAbsent(condition.group(), g -> new ArrayList<>())
                        .add(test);
            }
        }
        for (List<String> tests : groups.values()) {
            sql.append(" and (").append(String.join(" or ", tests)).append(')');
        }
        appendSearch(sql, params);
        return new SqlFragment(sql.toString(), params);
    }

    /** One condition as SQL over the field's expression, its values bound as parameters named after {@code p}. */
    private String test(Condition condition, String p, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder();
        String expr = condition.field().sql();
        List<Object> values = condition.values();
        String attributes = containmentColumn(condition.field());
        if (attributes != null && (condition.op() == QueryOp.EQ || condition.op() == QueryOp.IN)) {
            return containment(attributes, condition, p, params);
        }
        if (condition.field().type() == QueryFieldType.REF_SET) {
            return keySetTest(condition, p, params);
        }
        switch (condition.op()) {
            case EQ -> sql.append(expr).append(" = :").append(p);
            case NE -> sql.append(expr).append(" is distinct from :").append(p);
            case IN -> sql.append(expr).append(" in (:").append(p).append(')');
            case CONTAINS -> sql.append(expr).append(" ilike :").append(p).append(" escape '\\'");
            case STARTS_WITH -> sql.append(expr).append(" ilike :").append(p).append(" escape '\\'");
            case GT -> sql.append(expr).append(" > :").append(p);
            case GTE -> sql.append(expr).append(" >= :").append(p);
            case LT -> sql.append(expr).append(" < :").append(p);
            case LTE -> sql.append(expr).append(" <= :").append(p);
            case BETWEEN ->
                sql.append(expr)
                        .append(" between :")
                        .append(p)
                        .append("_a and :")
                        .append(p)
                        .append("_b");
            case EMPTY -> sql.append(emptyTest(condition.field(), true));
            case NOT_EMPTY -> sql.append(emptyTest(condition.field(), false));
        }
        switch (condition.op()) {
            case EMPTY, NOT_EMPTY -> {}
            case IN -> params.put(p, values);
            case BETWEEN -> {
                params.put(p + "_a", values.get(0));
                params.put(p + "_b", values.get(1));
            }
            case CONTAINS -> params.put(p, "%" + escapeLike((String) values.getFirst()) + "%");
            case STARTS_WITH -> params.put(p, escapeLike((String) values.getFirst()) + "%");
            default -> params.put(p, values.getFirst());
        }
        return sql.toString();
    }

    /**
     * The jsonb column of a text or choice custom field (plan 10/10, item 3.7): equality on it is written as
     * containment, which the GIN index of the column serves; {@code attributes->>'code' = ?} would scan the table.
     * Only string values are stored for these types, so {@code @>} finds exactly the rows the comparison would.
     */
    private @Nullable String containmentColumn(QueryField field) {
        boolean textual = field.type() == QueryFieldType.TEXT || field.type() == QueryFieldType.ENUM;
        return textual && field.attribute() != null ? list.attributesSql() : null;
    }

    /** {@code attributes @> {"code": value}} for each value; the key is bound as a parameter too. */
    private static String containment(String attributes, Condition condition, String p, Map<String, Object> params) {
        params.put(p + "_k", condition.field().attribute());
        List<String> tests = new ArrayList<>();
        List<Object> values = condition.values();
        for (int i = 0; i < values.size(); i++) {
            params.put(p + "_" + i, String.valueOf(values.get(i)));
            tests.add(attributes + " @> jsonb_build_object(cast(:" + p + "_k as text), cast(:" + p + "_" + i
                    + " as text))");
        }
        return tests.size() == 1 ? tests.getFirst() : "(" + String.join(" or ", tests) + ")";
    }

    private void appendSearch(StringBuilder sql, Map<String, Object> params) {
        List<QueryField> searchable = list.fields().stream()
                .filter(field -> field.searchable() && !hiddenFields.contains(field.key()))
                .toList();
        if (search != null && !searchable.isEmpty()) {
            sql.append(" and (");
            for (int i = 0; i < searchable.size(); i++) {
                if (i > 0) {
                    sql.append(" or ");
                }
                sql.append(searchable.get(i).sql()).append(" ilike :q_search escape '\\'");
            }
            sql.append(')');
            params.put("q_search", "%" + escapeLike(search) + "%");
        }
    }

    /** Continuation after the cursor: empty or {@code " and (...)"} over the pair (sort value, row key). */
    public SqlFragment keyset() {
        if (cursor == null) {
            return new SqlFragment("", Map.of());
        }
        String cmp = descending ? " < " : " > ";
        String expr = sort.sql();
        String id = list.idSql();
        String sql = " and (" + expr + cmp + ":q_after_value or (" + expr + " = :q_after_value and " + id + cmp
                + ":q_after_id))";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q_after_value", cursor.sortValue());
        params.put("q_after_id", idParameter(cursor.lastId()));
        return new SqlFragment(sql, params);
    }

    public String orderBy() {
        String direction = descending ? " desc" : " asc";
        return " order by " + sort.sql() + direction + ", " + list.idSql() + direction;
    }

    /**
     * A set of keys read as a {@code bigint[]} (plan 10/10, item 5.2): {@code in} holds when it shares a key with the
     * values, {@code empty} when it holds none.
     */
    private static String keySetTest(Condition condition, String p, Map<String, Object> params) {
        String expr = condition.field().sql();
        return switch (condition.op()) {
            case IN -> {
                params.put(p, condition.values());
                yield "(" + expr + " && cast(array[:" + p + "] as bigint[]))";
            }
            case EMPTY -> "(cardinality(" + expr + ") = 0)";
            case NOT_EMPTY -> "(cardinality(" + expr + ") > 0)";
            default -> throw new IllegalStateException("A set of keys takes in, empty and not_empty only");
        };
    }

    private static String emptyTest(QueryField field, boolean empty) {
        String expr = field.sql();
        if (field.type() == QueryFieldType.TEXT) {
            return empty ? "(" + expr + " is null or " + expr + " = '')" : "(" + expr + " <> '')";
        }
        return expr + (empty ? " is null" : " is not null");
    }

    /** A numeric key binds as a number (a bigint column), any other as text (a UUID read as {@code id::text}). */
    private static Object idParameter(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException notNumber) {
            return id;
        }
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
