package com.greenwhite.dwh.instance.common.query;

import com.greenwhite.dwh.instance.common.security.SecurityContext;

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
 * @param requiredForm   право, без которого поле не существует для смотрящего (ADR-0016, 2.9); null — поле открыто
 * @param requiredAction действие этого права
 * @param label          готовая подпись вместо ключа словаря: у дополнительного поля есть только имя (ADR-0019, 2.3)
 * @param attribute      код дополнительного поля: значение лежит в {@code attributes[attribute]} строки, а не в {@code key}
 */
public record QueryField(String key, String labelKey, QueryFieldType type, String sql, boolean filterable,
                         boolean sortable, boolean nullable, boolean defaultVisible, List<String> enumValues,
                         String enumLabelPrefix, boolean searchable, String requiredForm, String requiredAction,
                         String label, String attribute) {

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
        if ((requiredForm == null) != (requiredAction == null)) {
            throw new IllegalArgumentException("Query field " + key + ": a required right needs both form and action");
        }
        if ((type == QueryFieldType.ENUM) == enumValues.isEmpty()) {
            throw new IllegalArgumentException("Query field " + key + ": enum values go with the ENUM type only");
        }
    }

    public static QueryField of(String key, String labelKey, QueryFieldType type, String sql) {
        return new QueryField(key, labelKey, type, sql, true, false, false, true, List.of(), null, false, null, null, null, null);
    }

    public static QueryField enumeration(String key, String labelKey, String sql, List<String> values,
                                         String labelPrefix) {
        return new QueryField(key, labelKey, QueryFieldType.ENUM, sql, true, false, false, true, values, labelPrefix,
                false, null, null, null, null);
    }

    /**
     * A custom field of an entity (ADR-0019, 2.3): named by its own label, its value read from the row's
     * attributes. Never sortable — sorting needs an expression index the application cannot create.
     */
    public static QueryField custom(String key, String label, QueryFieldType type, String sql, String attribute,
                                    List<String> enumValues) {
        return new QueryField(key, "", type, sql, true, false, true, true, enumValues, null,
                type == QueryFieldType.TEXT, null, null, label, attribute);
    }

    public QueryField asSortable() {
        return new QueryField(key, labelKey, type, sql, filterable, true, nullable, defaultVisible, enumValues,
                enumLabelPrefix, searchable, requiredForm, requiredAction, label, attribute);
    }

    public QueryField asNullable() {
        return new QueryField(key, labelKey, type, sql, filterable, sortable, true, defaultVisible, enumValues,
                enumLabelPrefix, searchable, requiredForm, requiredAction, label, attribute);
    }

    public QueryField asNotFilterable() {
        return new QueryField(key, labelKey, type, sql, false, sortable, nullable, defaultVisible, enumValues,
                enumLabelPrefix, searchable, requiredForm, requiredAction, label, attribute);
    }

    public QueryField asSearchable() {
        return new QueryField(key, labelKey, type, sql, filterable, sortable, nullable, defaultVisible, enumValues,
                enumLabelPrefix, true, requiredForm, requiredAction, label, attribute);
    }

    public QueryField asHidden() {
        return new QueryField(key, labelKey, type, sql, filterable, sortable, nullable, false, enumValues,
                enumLabelPrefix, searchable, requiredForm, requiredAction, label, attribute);
    }

    /**
     * Поле только для тех, у кого есть право: без него поле не показывается в метаданных, не принимается
     * в фильтре, сортировке и поиске, а его значение не уходит в ответе списка.
     */
    public QueryField requires(String form, String action) {
        return new QueryField(key, labelKey, type, sql, filterable, sortable, nullable, defaultVisible, enumValues,
                enumLabelPrefix, searchable, form, action, label, attribute);
    }

    /** Видит ли поле тот, кто сейчас спрашивает. */
    public boolean visibleToViewer() {
        return requiredForm == null || SecurityContext.hasPermission(requiredForm, requiredAction);
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
