package com.smartup24.cms.instance.common.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A list description for the registry: where to read from, which fields exist and who sees them.
 * A module declares it as a bean; {@link QueryListRegistry} collects all such beans.
 *
 * @param code         list code in {@code /api/v1/query-meta/{code}} ({@code upl.sources})
 * @param form         form of the view permission; metadata is served under it too
 * @param action       action of the view permission
 * @param select       select list without {@code select}
 * @param from         source without {@code from}: the aliased table and joins
 * @param idSql        unique row key; completes the sort so the cursor is unambiguous
 * @param defaultSort  key of the default sort field
 * @param customEntity custom field entity ({@code TASK}); null means the list has none (ADR-0019)
 * @param attributesSql expression for the row's {@code attributes} column ({@code t.attributes})
 * @param estimatedTotal the first page reports the planner's estimate of the rows instead of counting them: for a
 *                       table that grows without bound, such as the audit log (plan 10/10, item 3.5)
 */
public record QueryList(
        String code,
        String form,
        String action,
        String select,
        String from,
        String idSql,
        List<QueryField> fields,
        String defaultSort,
        boolean defaultDescending,
        int defaultLimit,
        int maxLimit,
        @Nullable String customEntity,
        @Nullable String attributesSql,
        boolean estimatedTotal) {

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
        if (sort.requiredForm() != null) {
            throw new IllegalArgumentException(
                    "Query list " + code + ": the default sort must be open to every viewer");
        }
        if (defaultLimit < 1 || defaultLimit > maxLimit) {
            throw new IllegalArgumentException("Query list " + code + ": default limit out of range");
        }
    }

    public QueryList(
            String code,
            String form,
            String action,
            String select,
            String from,
            String idSql,
            List<QueryField> fields,
            String defaultSort,
            boolean defaultDescending,
            int defaultLimit,
            int maxLimit) {
        this(
                code,
                form,
                action,
                select,
                from,
                idSql,
                fields,
                defaultSort,
                defaultDescending,
                defaultLimit,
                maxLimit,
                null,
                null,
                false);
    }

    /** The same list with the custom fields of {@code entity}, read from {@code attributesSql}. */
    public QueryList withCustomFields(String entity, String attributesSql) {
        return new QueryList(
                code,
                form,
                action,
                select,
                from,
                idSql,
                fields,
                defaultSort,
                defaultDescending,
                defaultLimit,
                maxLimit,
                entity,
                attributesSql,
                estimatedTotal);
    }

    /** The same list with more fields after its own, e.g. the custom fields read at request time. */
    public QueryList withExtraFields(List<QueryField> extra) {
        if (extra.isEmpty()) return this;
        List<QueryField> all = new ArrayList<>(fields);
        all.addAll(extra);
        return new QueryList(
                code,
                form,
                action,
                select,
                from,
                idSql,
                all,
                defaultSort,
                defaultDescending,
                defaultLimit,
                maxLimit,
                customEntity,
                attributesSql,
                estimatedTotal);
    }

    /** The same list with its own fields as they are now (an enumeration's values, ADR-0032, 4.5). */
    public QueryList withFields(List<QueryField> current) {
        return new QueryList(
                code,
                form,
                action,
                select,
                from,
                idSql,
                current,
                defaultSort,
                defaultDescending,
                defaultLimit,
                maxLimit,
                customEntity,
                attributesSql,
                estimatedTotal);
    }

    /** The same list reporting an estimate of its rows instead of a count (plan 10/10, item 3.5). */
    public QueryList withEstimatedTotal() {
        return new QueryList(
                code,
                form,
                action,
                select,
                from,
                idSql,
                fields,
                defaultSort,
                defaultDescending,
                defaultLimit,
                maxLimit,
                customEntity,
                attributesSql,
                true);
    }

    public QueryList(
            String code,
            String form,
            String action,
            String select,
            String from,
            String idSql,
            List<QueryField> fields,
            String defaultSort) {
        this(code, form, action, select, from, idSql, fields, defaultSort, false, DEFAULT_LIMIT, MAX_LIMIT);
    }

    public Optional<QueryField> field(String key) {
        return fields.stream().filter(field -> field.key().equals(key)).findFirst();
    }

    /** Fields the current requester can see (field permissions, ADR-0016). */
    public List<QueryField> viewerFields() {
        return fields.stream().filter(QueryField::visibleToViewer).toList();
    }

    /** The field by key if the viewer can see it; a hidden field is indistinguishable from a missing one. */
    public Optional<QueryField> viewerField(String key) {
        return field(key).filter(QueryField::visibleToViewer);
    }
}
