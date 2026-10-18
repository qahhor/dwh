package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** Combines a saved filter with a narrowing period; the query compiler validates the combined limits (ADR-0036). */
final class ReportFilters {
    private static final int MAX_CHARS = 16_384;
    private static final JsonMapper JSON = JsonMapper.shared();

    private ReportFilters() {}

    /** The values of a report's parameters: a JSON object of at most 16 KiB, null when none are given (ADR-0044). */
    static @Nullable JsonNode object(@Nullable String values) {
        if (values == null || values.isBlank()) return null;
        try {
            if (values.length() > MAX_CHARS) throw new IllegalArgumentException("too long");
            JsonNode root = JSON.readTree(values);
            if (root.isObject()) return root;
        } catch (JacksonException | IllegalArgumentException failure) {
            throw parametersInvalid();
        }
        throw parametersInvalid();
    }

    private static ApiException parametersInvalid() {
        return ApiException.validation(
                "error.common.report_parameters_invalid",
                List.of(FieldErrorItem.keyed(
                        "parameters", ReportParameters.INVALID, "error.common.report_parameters_invalid")));
    }

    static @Nullable String and(@Nullable String saved, @Nullable String extra) {
        if (extra == null || extra.isBlank()) return saved;
        ArrayNode result = JSON.createArrayNode();
        append(result, saved);
        append(result, extra);
        String combined = result.toString();
        checkLength(combined);
        return combined;
    }

    private static void append(ArrayNode target, @Nullable String filter) {
        if (filter == null || filter.isBlank()) return;
        checkLength(filter);
        try {
            var root = JSON.readTree(filter);
            if (!root.isArray()) throw invalid("error.common.field_filter_not_array");
            root.forEach(target::add);
        } catch (JacksonException failure) {
            throw invalid("error.common.field_filter_not_json");
        }
    }

    private static void checkLength(String filter) {
        if (filter.length() > MAX_CHARS) {
            throw ApiException.validation(
                    "error.common.query_invalid",
                    List.of(FieldErrorItem.keyed(
                            "filter", QueryCompiler.FILTER_TOO_LONG, "error.common.field_filter_too_long")));
        }
    }

    private static ApiException invalid(String key) {
        return ApiException.validation(
                "error.common.query_invalid",
                List.of(FieldErrorItem.keyed("filter", QueryCompiler.FILTER_INVALID, key)));
    }
}
