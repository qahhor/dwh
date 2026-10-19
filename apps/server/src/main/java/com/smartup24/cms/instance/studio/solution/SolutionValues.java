package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Values that name records of this installation (ADR-0047): a filter, a report parameter's default or a mailing's
 * parameter value on a field that refers to records, users or units by id. Such a value means nothing in another
 * installation, so a package refuses to carry it; the codes of list items and choices travel as they are.
 */
final class SolutionValues {
    private SolutionValues() {}

    /** The conditions of the filter (and of its groups) on a field that refers by id, with a value given. */
    static void filter(QueryList list, @Nullable JsonNode filter, String at, List<FieldErrorItem> problems) {
        if (filter == null) return;
        if (filter.isArray()) {
            filter.forEach(part -> filter(list, part, at, problems));
            return;
        }
        if (!filter.isObject()) return;
        JsonNode field = filter.get("field");
        if (field != null && field.isString()) {
            JsonNode value = filter.get("value");
            if (value != null && !value.isNull() && refersById(list, field.asString()))
                problems.add(problem(at, field.asString()));
            return;
        }
        for (String key : filter.propertyNames()) filter(list, filter.get(key), at, problems);
    }

    /** The defaults of a report's parameters on a field that refers by id. */
    static void parameters(QueryList list, @Nullable JsonNode parameters, String at, List<FieldErrorItem> problems) {
        if (parameters == null || !parameters.isArray()) return;
        for (JsonNode parameter : parameters) {
            JsonNode value = parameter.get("default");
            String field = parameter.path("field").asString("");
            if (value != null && !value.isNull() && refersById(list, field)) problems.add(problem(at, field));
        }
    }

    /** The values a mailing gives the parameters of its report, for a parameter on a field that refers by id. */
    static void values(
            QueryList list,
            @Nullable JsonNode parameters,
            Map<String, Object> values,
            String at,
            List<FieldErrorItem> problems) {
        if (parameters == null || !parameters.isArray()) return;
        for (JsonNode parameter : parameters) {
            String key = parameter.path("key").asString("");
            String field = parameter.path("field").asString("");
            if (values.get(key) != null && refersById(list, field)) problems.add(problem(at, field));
        }
    }

    static boolean refersById(QueryList list, String key) {
        QueryField field = list.field(key).orElse(null);
        if (field == null) return false;
        return field.type() == QueryFieldType.REF_SET || (field.ref() != null && field.type() != QueryFieldType.ENUM);
    }

    private static FieldErrorItem problem(String at, String field) {
        return FieldErrorItem.keyed(
                at, "solution_record_value", "error.studio.solution_record_value", Map.of("field", field));
    }
}
