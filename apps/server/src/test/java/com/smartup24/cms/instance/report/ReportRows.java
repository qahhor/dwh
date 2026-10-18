package com.smartup24.cms.instance.report;

import java.util.List;
import java.util.Map;

/**
 * The rows of a report answer by their group values and measures only: the labels every row carries (ADR-0036, the
 * form and report builder) are checked by the tests of the labels themselves.
 */
final class ReportRows {
    private ReportRows() {}

    @SuppressWarnings("unchecked")
    static List<Object> values(Map<String, Object> report) {
        return ((List<Map<String, Object>>) report.get("rows"))
                .stream()
                        .<Object>map(row -> Map.of("groups", row.get("groups"), "values", row.get("values")))
                        .toList();
    }

    @SuppressWarnings("unchecked")
    static List<Object> labels(Map<String, Object> report) {
        return ((List<Map<String, Object>>) report.get("rows"))
                .stream().map(row -> row.get("labels")).toList();
    }
}
