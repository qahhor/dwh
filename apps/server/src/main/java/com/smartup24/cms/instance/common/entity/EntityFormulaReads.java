package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How a formula reads a record and the rows of its collections (ADR-0042, ADR-0043): a field by its key, the values of
 * a column of a collection's rows by {@code lines.<collection>.<field>} — a list in the rows' order — and the rows
 * themselves by {@code lines.<collection>}; a formula of a row reads the row by key and the header by
 * {@code doc.<key>}. The checker accepted only the names the form has, so a name read here is one of them.
 */
public final class EntityFormulaReads {
    /** The prefix of a name that reads the rows of a collection. */
    public static final String LINES = "lines.";

    /** The prefix of a name a row's formula reads the header by. */
    public static final String DOC = "doc.";

    private EntityFormulaReads() {}

    /** The values of a record as read: its fields and, by {@code lines.*}, the rows it holds under their keys. */
    public static Function<String, @Nullable Object> record(Map<String, ?> record) {
        return key -> key.startsWith(LINES) ? rows(key, collection -> listed(record.get(collection))) : record.get(key);
    }

    /** The values of a save as its rules see them: its fields and the rows of its collections. */
    public static Function<String, @Nullable Object> values(EntityValues values) {
        return key -> key.startsWith(LINES) ? rows(key, values::collection) : values.get(key);
    }

    /** The values a formula of a row reads: the row by key and the header by {@code doc.<key>}. */
    public static Function<String, @Nullable Object> row(Map<String, ?> row, Map<String, ?> record) {
        return key -> key.startsWith(DOC) ? record.get(key.substring(DOC.length())) : row.get(key);
    }

    /** The rows of {@code lines.<collection>}, or the column of {@code lines.<collection>.<field>}. */
    private static List<?> rows(String name, Function<String, List<? extends Map<String, ?>>> collections) {
        String rest = name.substring(LINES.length());
        int dot = rest.indexOf('.');
        List<? extends Map<String, ?>> rows = collections.apply(dot < 0 ? rest : rest.substring(0, dot));
        if (dot < 0) return rows;
        String field = rest.substring(dot + 1);
        List<@Nullable Object> column = new ArrayList<>(rows.size());
        rows.forEach(row -> column.add(row.get(field)));
        return column;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, ?>> listed(@Nullable Object value) {
        List<Map<String, ?>> rows = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> row) rows.add((Map<String, ?>) row);
            }
        }
        return rows;
    }
}
