package com.greenwhite.dwh.instance.common.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Описание списка для реестра: откуда читать, какие поля есть и кто их видит.
 * Модуль объявляет его бином; {@link QueryListRegistry} собирает все такие бины.
 *
 * @param code         код списка в {@code /api/v1/query-meta/{code}} ({@code upl.sources})
 * @param form         форма права на просмотр; по ней же отдаются метаданные
 * @param action       действие права на просмотр
 * @param select       список выборки без {@code select}
 * @param from         источник без {@code from}: таблица с алиасом и соединения
 * @param idSql        уникальный ключ строки; добивает сортировку, чтобы курсор был однозначным
 * @param defaultSort  ключ поля сортировки по умолчанию
 */
public record QueryList(String code, String form, String action, String select, String from, String idSql,
                        List<QueryField> fields, String defaultSort, boolean defaultDescending, int defaultLimit,
                        int maxLimit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public QueryList {
        fields = List.copyOf(fields);
        Map<String, QueryField> byKey = new LinkedHashMap<>();
        for (QueryField field : fields) {
            if (byKey.put(field.key(), field) != null) {
                throw new IllegalArgumentException("Query list " + code + ": duplicate field " + field.key());
            }
        }
        QueryField sort = byKey.get(defaultSort);
        if (sort == null || !sort.sortable()) {
            throw new IllegalArgumentException("Query list " + code + ": default sort must be a sortable field");
        }
        if (defaultLimit < 1 || defaultLimit > maxLimit) {
            throw new IllegalArgumentException("Query list " + code + ": default limit out of range");
        }
    }

    public QueryList(String code, String form, String action, String select, String from, String idSql,
                     List<QueryField> fields, String defaultSort) {
        this(code, form, action, select, from, idSql, fields, defaultSort, false, DEFAULT_LIMIT, MAX_LIMIT);
    }

    public Optional<QueryField> field(String key) {
        return fields.stream().filter(field -> field.key().equals(key)).findFirst();
    }
}
