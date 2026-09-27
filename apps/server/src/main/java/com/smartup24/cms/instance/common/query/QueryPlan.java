package com.smartup24.cms.instance.common.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Проверенный запрос к списку: условия с типизированными значениями, сортировка, размер страницы и курсор.
 * Строится только {@link QueryCompiler}; SQL собирается из выражений реестра, значения идут параметрами.
 */
public record QueryPlan(QueryList list, List<Condition> conditions, QueryField sort, boolean descending, int limit,
                        QueryCursor cursor, String fingerprint, String search, Set<String> hiddenFields) {

    /**
     * Условие фильтра; {@code values} уже приведены к типу поля. {@code group} — номер группы «или»
     * ({@code {"any": [...]}}), {@code -1} — условие соединяется с остальными через «и».
     */
    public record Condition(QueryField field, QueryOp op, List<Object> values, int group) {

        public Condition(QueryField field, QueryOp op, List<Object> values) {
            this(field, op, values, -1);
        }

        public Condition inGroup(int group) {
            return new Condition(field, op, values, group);
        }
    }

    /** Кусок SQL с его параметрами. Параметры реестра начинаются с {@code q_}, чтобы не спорить с параметрами модуля. */
    public record SqlFragment(String sql, Map<String, Object> params) {
    }

    public QueryPlan {
        conditions = List.copyOf(conditions);
        hiddenFields = Set.copyOf(hiddenFields);
    }

    /**
     * Отдаёт ли список значение поля этому смотрящему. Модуль, собирая строку ответа, оставляет поле
     * пустым, если нет (права на поля, ADR-0016, 2.9); пустое поле в JSON не пишется.
     */
    public boolean shows(String key) {
        return !hiddenFields.contains(key);
    }

    /** Условия фильтра: пусто или {@code " and ..."}; условия одной группы «или» — в скобках через {@code or}. */
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
                groups.computeIfAbsent(condition.group(), g -> new ArrayList<>()).add(test);
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
            case BETWEEN -> sql.append(expr).append(" between :").append(p).append("_a and :").append(p).append("_b");
            case EMPTY -> sql.append(emptyTest(condition.field(), true));
            case NOT_EMPTY -> sql.append(emptyTest(condition.field(), false));
        }
        switch (condition.op()) {
            case EMPTY, NOT_EMPTY -> {
            }
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

    private void appendSearch(StringBuilder sql, Map<String, Object> params) {
        List<QueryField> searchable = list.fields().stream()
                .filter(field -> field.searchable() && !hiddenFields.contains(field.key())).toList();
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

    /** Продолжение после курсора: пусто или {@code " and (...)"} по паре «значение сортировки, ключ строки». */
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
