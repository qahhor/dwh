package com.greenwhite.dwh.instance.common.query;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Поле списка в реестре. {@code sql} — выражение над {@code from} списка; оно пишется в коде модуля
 * и в запрос попадает только через реестр, значения — только параметрами. Клиенту уходит всё, кроме {@code sql}.
 *
 * @param key            имя поля в DSL и в ответе ({@code lastPublishedVersion})
 * @param labelKey       ключ словаря для заголовка
 * @param enumLabelPrefix префикс ключей словаря для значений перечисления ({@code upl.periodicity.})
 * @param sortable       можно сортировать; такое поле не бывает пустым, иначе keyset-курсор теряет строки
 * @param searchable     участвует в свободном поиске {@code q} (только текст)
 */
public record QueryField(String key, String labelKey, QueryFieldType type, String sql, boolean filterable,
                         boolean sortable, boolean nullable, boolean defaultVisible, List<String> enumValues,
                         String enumLabelPrefix, boolean searchable) {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{0,63}$");

    public QueryField {
        Objects.requireNonNull(type, "type");
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Bad query field key: " + key);
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Query field " + key + " has no sql");
        }
        if (sortable && nullable) {
            throw new IllegalArgumentException("Query field " + key + " is sortable, so it must not be nullable");
        }
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        if (searchable && type != QueryFieldType.TEXT) {
            throw new IllegalArgumentException("Query field " + key + ": only text fields are searchable");
        }
        if ((type == QueryFieldType.ENUM) == enumValues.isEmpty()) {
            throw new IllegalArgumentException("Query field " + key + ": enum values go with the ENUM type only");
        }
    }

    public static QueryField of(String key, String labelKey, QueryFieldType type, String sql) {
        return new QueryField(key, labelKey, type, sql, true, false, false, true, List.of(), null, false);
    }

    public static QueryField enumeration(String key, String labelKey, String sql, List<String> values,
                                         String labelPrefix) {
        return new QueryField(key, labelKey, QueryFieldType.ENUM, sql, true, false, false, true, values, labelPrefix,
                false);
    }

    public QueryField asSortable() {
        return new QueryField(key, labelKey, type, sql, filterable, true, nullable, defaultVisible, enumValues,
                enumLabelPrefix, searchable);
    }

    public QueryField asNullable() {
        return new QueryField(key, labelKey, type, sql, filterable, sortable, true, defaultVisible, enumValues,
                enumLabelPrefix, searchable);
    }

    public QueryField asNotFilterable() {
        return new QueryField(key, labelKey, type, sql, false, sortable, nullable, defaultVisible, enumValues,
                enumLabelPrefix, searchable);
    }

    public QueryField asSearchable() {
        return new QueryField(key, labelKey, type, sql, filterable, sortable, nullable, defaultVisible, enumValues,
                enumLabelPrefix, true);
    }

    public QueryField asHidden() {
        return new QueryField(key, labelKey, type, sql, filterable, sortable, nullable, false, enumValues,
                enumLabelPrefix, searchable);
    }

    /** Операции, которые поле принимает в фильтре; пусто — поле не фильтруется. */
    public Set<QueryOp> ops() {
        if (!filterable) {
            return EnumSet.noneOf(QueryOp.class);
        }
        Set<QueryOp> ops = type.ops();
        if (nullable) {
            ops.add(QueryOp.EMPTY);
            ops.add(QueryOp.NOT_EMPTY);
        }
        return ops;
    }
}
