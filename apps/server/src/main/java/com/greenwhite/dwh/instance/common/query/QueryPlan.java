package com.greenwhite.dwh.instance.common.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Проверенный запрос к списку: условия с типизированными значениями, сортировка, размер страницы и курсор.
 * Строится только {@link QueryCompiler}; SQL собирается из выражений реестра, значения идут параметрами.
 */
public record QueryPlan(QueryList list, List<Condition> conditions, QueryField sort, boolean descending, int limit,
                        QueryCursor cursor, String fingerprint, String search) {

    /** Условие фильтра; {@code values} уже приведены к типу поля. */
    public record Condition(QueryField field, QueryOp op, List<Object> values) {
    }

    /** Кусок SQL с его параметрами. Параметры реестра начинаются с {@code q_}, чтобы не спорить с параметрами модуля. */
    public record SqlFragment(String sql, Map<String, Object> params) {
    }

    public QueryPlan {
        conditions = List.copyOf(conditions);
    }

    /** Условия фильтра: пусто или {@code " and ..."}. */
    public SqlFragment where() {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < conditions.size(); i++) {
            Condition condition = conditions.get(i);
            String expr = condition.field().sql();
            String p = "q_f" + i;
            List<Object> values = condition.values();
            sql.append(" and ");
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
        }
        List<QueryField> searchable = list.fields().stream().filter(QueryField::searchable).toList();
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
        return new SqlFragment(sql.toString(), params);
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
        params.put("q_after_id", cursor.lastId());
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

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
